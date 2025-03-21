/*
 * Copyright (c) 2016, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.host;

import java.lang.invoke.MethodHandles;
import java.lang.ref.Reference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.UnmodifiableEconomicMap;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.host.HostAdapterFactory.AdapterResult;
import com.oracle.truffle.host.HostMethodDesc.OverloadedMethod;
import com.oracle.truffle.host.HostMethodDesc.SingleMethod;

final class HostClassDesc {
    @TruffleBoundary
    static HostClassDesc forClass(HostContext context, Class<?> clazz) {
        return context.getHostClassCache().forClass(clazz);
    }

    @TruffleBoundary
    static HostClassDesc forClass(HostClassCache cache, Class<?> clazz) {
        return cache.forClass(clazz);
    }

    private final Class<?> type;
    private final Reference<HostClassCache> cache;
    private volatile Members members;
    private volatile JNIMembers jniMembers;
    private volatile MethodsBySignature methodsBySignature;
    private volatile AdapterResult adapter;
    private final boolean allowsImplementation;
    private final boolean allowedTargetType;

    HostClassDesc(Reference<HostClassCache> cacheRef, Class<?> type) {
        this.type = type;
        this.cache = cacheRef;
        this.allowsImplementation = HostInteropReflect.isExtensibleType(type) && getCache().allowsImplementation(type);
        this.allowedTargetType = allowsImplementation && HostInteropReflect.isAbstractType(type) && hasDefaultConstructor(type);
    }

    public boolean isAllowsImplementation() {
        return allowsImplementation;
    }

    public boolean isAllowedTargetType() {
        return allowedTargetType;
    }

    public Class<?> getType() {
        return type;
    }

    private static boolean hasDefaultConstructor(Class<?> type) {
        assert !type.isPrimitive();
        if (type.isInterface()) {
            return true;
        } else {
            for (Constructor<?> ctor : type.getConstructors()) {
                if (ctor.getParameterCount() == 0) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * @return lookup for accessing the specified class, or <code>null</code> if the class is not
     *         accessible.
     */
    static MethodHandles.Lookup getLookup(Class<?> clazz, HostClassCache hostAccess) {
        try {
            MethodHandles.Lookup lookup = hostAccess.getMethodLookup(clazz);
            lookup.accessClass(clazz);
            return lookup;
        } catch (IllegalAccessException e) {
            // unfortunately there is no better way to detect access to a class
            // than by catching IllegalAccessException
            return null;
        }
    }

    private static class Members {
        final Map<String, HostMethodDesc> methods;
        final Map<String, HostMethodDesc> staticMethods;
        final HostMethodDesc constructor;
        final Map<String, HostFieldDesc> fields;
        final Map<String, HostFieldDesc> staticFields;
        final HostMethodDesc functionalMethod;

        private static final BiFunction<HostMethodDesc, HostMethodDesc, HostMethodDesc> MERGE = new BiFunction<>() {
            @Override
            public HostMethodDesc apply(HostMethodDesc m1, HostMethodDesc m2) {
                return merge(m1, m2);
            }
        };

        Members(HostClassCache hostAccess, Class<?> type) {
            Map<String, HostMethodDesc> methodMap = new LinkedHashMap<>();
            Map<String, HostMethodDesc> staticMethodMap = new LinkedHashMap<>();
            Map<String, HostFieldDesc> fieldMap = new LinkedHashMap<>();
            Map<String, HostFieldDesc> staticFieldMap = new LinkedHashMap<>();
            HostMethodDesc functionalInterfaceMethodImpl = null;

            collectPublicMethods(hostAccess, type, methodMap, staticMethodMap);
            collectPublicFields(hostAccess, type, fieldMap, staticFieldMap);

            HostMethodDesc ctor = collectPublicConstructors(hostAccess, type);

            if (!Modifier.isInterface(type.getModifiers()) && !Modifier.isAbstract(type.getModifiers())) {
                Method implementableAbstractMethod = findFunctionalInterfaceMethod(hostAccess, type);
                if (implementableAbstractMethod != null) {
                    functionalInterfaceMethodImpl = lookupAbstractMethodImplementation(implementableAbstractMethod, methodMap);
                }
            }

            this.methods = methodMap;
            this.staticMethods = staticMethodMap;
            this.constructor = ctor;
            this.fields = fieldMap;
            this.staticFields = staticFieldMap;
            this.functionalMethod = functionalInterfaceMethodImpl;
        }

        /**
         * @return lookup for accessing the specified class, or <code>null</code> if the class is
         *         not accessible or not public.
         */
        private static MethodHandles.Lookup getLookupForPublicClass(Class<?> declaringClass, HostClassCache hostAccess) {
            if (Modifier.isPublic(declaringClass.getModifiers())) {
                return getLookup(declaringClass, hostAccess);
            } else {
                return null;
            }
        }

        private static HostMethodDesc collectPublicConstructors(HostClassCache hostAccess, Class<?> type) {
            HostMethodDesc ctor = null;
            MethodHandles.Lookup lookup;
            if ((lookup = getLookupForPublicClass(type, hostAccess)) != null && !Modifier.isAbstract(type.getModifiers())) {
                for (Constructor<?> c : type.getConstructors()) {
                    if (!hostAccess.allowsAccess(c)) {
                        continue;
                    }
                    boolean scoped = hostAccess.methodScoped(c);
                    SingleMethod overload = SingleMethod.unreflect(lookup, c, scoped);
                    ctor = ctor == null ? overload : merge(ctor, overload);
                }
            }
            return ctor;
        }

        private static void collectPublicMethods(HostClassCache hostAccess, Class<?> type, Map<String, HostMethodDesc> methodMap, Map<String, HostMethodDesc> staticMethodMap) {
            collectPublicMethods(hostAccess, type, methodMap, staticMethodMap, new HashMap<>(), type);
        }

        private static void collectPublicMethods(HostClassCache hostAccess, Class<?> type, Map<String, HostMethodDesc> methodMap, Map<String, HostMethodDesc> staticMethodMap,
                        Map<Object, Object> visited,
                        Class<?> startType) {
            boolean isPublicType = getLookupForPublicClass(type, hostAccess) != null && !Proxy.isProxyClass(type);
            boolean includeInherited = hostAccess.allowsPublicAccess || hostAccess.allowsAccessInheritance;
            List<Method> bridgeMethods = null;
            /**
             * If we do not allow inheriting access, we discover all accessible methods through the
             * public methods of this class. That way, (possibly inaccessible) method overrides hide
             * inherited, otherwise accessible methods.
             */
            if (isPublicType || !includeInherited) {
                for (Method m : type.getMethods()) {
                    Class<?> declaringClass = m.getDeclaringClass();
                    if (Modifier.isStatic(m.getModifiers()) && (declaringClass != startType && Modifier.isInterface(declaringClass.getModifiers()))) {
                        // do not inherit static interface methods
                        continue;
                    } else if (getLookupForPublicClass(declaringClass, hostAccess) == null && !Proxy.isProxyClass(declaringClass)) {
                        // the declaring class and the method itself must be public and accessible
                        continue;
                    } else if (m.isBridge() && hostAccess.allowsPublicAccess && hostAccess.allowsAccess(m)) {
                        /*
                         * Bridge methods for varargs methods generated by javac may not have the
                         * varargs modifier, so we must not use the bridge method in that case since
                         * it would be then treated as non-varargs.
                         *
                         * As a workaround, stash away all bridge methods and only consider them at
                         * the end if no equivalent public non-bridge method was found.
                         */
                        if (bridgeMethods == null) {
                            bridgeMethods = new ArrayList<>();
                        }
                        bridgeMethods.add(m);
                        continue;
                    } else if (m.isBridge()) {
                        /*
                         * GR-42882] Without public access we skip all bridge methods. This is
                         * needed due to inconsistent behavior between javac and JDT. JDT does not
                         * inherit exported methods for bridge methods.
                         */
                        continue;
                    }
                    if (hostAccess.allowsAccess(m)) {
                        collectPublicMethod(hostAccess, methodMap, staticMethodMap, visited, m);
                    }
                }
                if (hostAccess.isArrayAccess() && type.isArray()) {
                    SingleMethod arrayCloneMethod = new SingleMethod.SyntheticArrayCloneMethod(type);
                    methodMap.put(arrayCloneMethod.getName(), arrayCloneMethod);
                }
            }
            if (includeInherited) {
                // Look for accessible inherited public methods, if allowed.
                if (type.getSuperclass() != null) {
                    collectPublicMethods(hostAccess, type.getSuperclass(), methodMap, staticMethodMap, visited, startType);
                }
                for (Class<?> intf : type.getInterfaces()) {
                    if (visited.put(intf, intf) == null) {
                        collectPublicMethods(hostAccess, intf, methodMap, staticMethodMap, visited, startType);
                    }
                }
            }
            // Add bridge methods for public methods inherited from non-public superclasses.
            // See https://bugs.openjdk.java.net/browse/JDK-6342411
            if (bridgeMethods != null && !bridgeMethods.isEmpty()) {
                for (Method m : bridgeMethods) {
                    assert hostAccess.allowsAccess(m); // already checked above
                    collectPublicMethod(hostAccess, methodMap, staticMethodMap, visited, m);
                }
            }
        }

        private static void collectPublicMethod(HostClassCache hostAccess, Map<String, HostMethodDesc> methodMap, Map<String, HostMethodDesc> staticMethodMap, Map<Object, Object> visited,
                        Method m) {
            MethodInfo methodInfo = methodInfo(m);
            if (!visited.containsKey(methodInfo)) {
                visited.put(methodInfo, methodInfo);
                putMethod(hostAccess, m, methodMap, staticMethodMap, false);
            } else {
                // could be inherited method with different return type
                // include those for jni-naming scheme lookup too
                MethodInfo info = (MethodInfo) visited.get(methodInfo);
                if (info.returnType != methodInfo.returnType) {
                    putMethod(hostAccess, m, methodMap, staticMethodMap, true);
                }
            }
        }

        private static MethodInfo methodInfo(Method m) {
            return new MethodInfo(m);
        }

        private static class MethodInfo {
            private final boolean isStatic;
            private final String name;
            private final Class<?>[] parameterTypes;
            private final Class<?> returnType; // only used for jni name lookup

            MethodInfo(Method m) {
                this.isStatic = Modifier.isStatic(m.getModifiers());
                this.name = m.getName();
                this.parameterTypes = m.getParameterTypes();
                this.returnType = m.getReturnType();
            }

            @Override
            public boolean equals(Object obj) {
                if (obj instanceof MethodInfo) {
                    MethodInfo other = (MethodInfo) obj;
                    return isStatic == other.isStatic && name.equals(other.name) && Arrays.equals(parameterTypes, other.parameterTypes);
                } else {
                    return false;
                }
            }

            @Override
            public int hashCode() {
                final int prime = 31;
                int result = 1;
                result = prime * result + (isStatic ? 1 : 0);
                result = prime * result + name.hashCode();
                result = prime * result + Arrays.hashCode(parameterTypes);
                return result;
            }
        }

        private static void putMethod(HostClassCache hostAccess, Method m, Map<String, HostMethodDesc> methodMap, Map<String, HostMethodDesc> staticMethodMap, boolean onlyVisibleFromJniName) {
            assert hostAccess.allowsAccess(m);
            boolean scoped = hostAccess.methodScoped(m);
            SingleMethod method = SingleMethod.unreflect(hostAccess.getMethodLookup(m.getDeclaringClass()), m, scoped, onlyVisibleFromJniName);
            Map<String, HostMethodDesc> map = Modifier.isStatic(m.getModifiers()) ? staticMethodMap : methodMap;
            map.merge(m.getName(), method, MERGE);
        }

        static HostMethodDesc merge(HostMethodDesc existing, HostMethodDesc other) {
            assert other instanceof SingleMethod;
            if (existing instanceof SingleMethod) {
                return new OverloadedMethod(new SingleMethod[]{(SingleMethod) existing, (SingleMethod) other});
            } else {
                SingleMethod[] oldOverloads = existing.getOverloads();
                SingleMethod[] newOverloads = Arrays.copyOf(oldOverloads, oldOverloads.length + 1);
                newOverloads[oldOverloads.length] = (SingleMethod) other;
                return new OverloadedMethod(newOverloads);
            }
        }

        private static void collectPublicFields(HostClassCache hostAccess, Class<?> type, Map<String, HostFieldDesc> fieldMap, Map<String, HostFieldDesc> staticFieldMap) {
            MethodHandles.Lookup lookup;
            if ((lookup = getLookupForPublicClass(type, hostAccess)) != null) {
                boolean inheritedPublicInstanceFields = false;
                boolean inheritedPublicInaccessibleFields = false;
                for (Field f : type.getFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) {
                        if (f.getDeclaringClass() == type) {
                            assert !fieldMap.containsKey(f.getName());
                            if (hostAccess.allowsAccess(f)) {
                                fieldMap.put(f.getName(), HostFieldDesc.unreflect(lookup, f));
                            }
                        } else {
                            if (getLookupForPublicClass(f.getDeclaringClass(), hostAccess) != null) {
                                inheritedPublicInstanceFields = true;
                            } else {
                                inheritedPublicInaccessibleFields = true;
                            }
                        }
                    } else {
                        // do not inherit static fields
                        if (f.getDeclaringClass() == type && hostAccess.allowsAccess(f)) {
                            staticFieldMap.put(f.getName(), HostFieldDesc.unreflect(lookup, f));
                        }
                    }
                }
                if (inheritedPublicInstanceFields) {
                    collectPublicInstanceFields(hostAccess, type, fieldMap, inheritedPublicInaccessibleFields);
                }
                if (hostAccess.isArrayAccess() && type.isArray()) {
                    HostFieldDesc arrayLengthField = HostFieldDesc.SyntheticArrayLengthField.SINGLETON;
                    fieldMap.put(arrayLengthField.getName(), arrayLengthField);
                }
            } else {
                if (!Modifier.isInterface(type.getModifiers())) {
                    collectPublicInstanceFields(hostAccess, type, fieldMap, true);
                }
            }
        }

        private static void collectPublicInstanceFields(HostClassCache hostAccess, Class<?> type, Map<String, HostFieldDesc> fieldMap, boolean mayHaveInaccessibleFields) {
            Set<String> fieldNames = new HashSet<>();
            for (Class<?> superclass = type; superclass != null && superclass != Object.class; superclass = superclass.getSuperclass()) {
                boolean inheritedPublicInstanceFields = false;
                for (Field f : superclass.getFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    if (f.getDeclaringClass() != superclass) {
                        if (Modifier.isPublic(f.getDeclaringClass().getModifiers())) {
                            inheritedPublicInstanceFields = true;
                        }
                        continue;
                    }
                    // a public field in a non-public class hides fields further up the hierarchy
                    if (mayHaveInaccessibleFields && !fieldNames.add(f.getName())) {
                        continue;
                    }
                    MethodHandles.Lookup lookup;
                    if ((lookup = getLookupForPublicClass(f.getDeclaringClass(), hostAccess)) != null) {
                        if (hostAccess.allowsAccess(f)) {
                            fieldMap.putIfAbsent(f.getName(), HostFieldDesc.unreflect(lookup, f));
                        }
                    } else {
                        assert mayHaveInaccessibleFields;
                    }
                }
                if (!inheritedPublicInstanceFields) {
                    break;
                }
            }
        }

        private static Method findFunctionalInterfaceMethod(HostClassCache hostAccess, Class<?> clazz) {
            for (Class<?> iface : clazz.getInterfaces()) {
                if (getLookupForPublicClass(iface, hostAccess) != null && iface.isAnnotationPresent(FunctionalInterface.class)) {
                    for (Method m : iface.getMethods()) {
                        if (Modifier.isAbstract(m.getModifiers()) && !isObjectMethodOverride(m)) {
                            return m;
                        }
                    }
                }
            }

            Class<?> superclass = clazz.getSuperclass();
            if (superclass != null && superclass != Object.class) {
                return findFunctionalInterfaceMethod(hostAccess, superclass);
            }
            return null;
        }

        private static HostMethodDesc lookupAbstractMethodImplementation(Method abstractMethod, Map<String, HostMethodDesc> methodMap) {
            HostMethodDesc accessibleMethodDesc = methodMap.get(abstractMethod.getName());
            if (accessibleMethodDesc != null) {
                Class<?>[] searchTypes = abstractMethod.getParameterTypes();
                SingleMethod[] available = accessibleMethodDesc.getOverloads();
                List<SingleMethod> candidates = new ArrayList<>(available.length);
                next: for (SingleMethod candidate : available) {
                    Class<?>[] candidateTypes = candidate.getParameterTypes();
                    if (searchTypes.length == candidateTypes.length) {
                        for (int i = 0; i < searchTypes.length; i++) {
                            if (candidateTypes[i].isAssignableFrom(searchTypes[i])) {
                                // allow covariant parameter types
                                continue;
                            } else if (searchTypes[i].isAssignableFrom(candidateTypes[i])) {
                                // allow contravariant generic type parameters
                                continue;
                            } else {
                                continue next;
                            }
                        }
                        candidates.add(candidate);
                    }
                }
                if (candidates.size() == available.length) {
                    return accessibleMethodDesc;
                } else if (candidates.size() == 1) {
                    return candidates.get(0);
                } else if (candidates.size() > 1) {
                    return new OverloadedMethod(candidates.toArray(new SingleMethod[candidates.size()]));
                }
            }
            return null;
        }
    }

    static boolean isObjectMethodOverride(Method m) {
        return ((m.getParameterCount() == 0 && (m.getName().equals("hashCode") || m.getName().equals("toString"))) ||
                        (m.getParameterCount() == 1 && m.getName().equals("equals") && m.getParameterTypes()[0] == Object.class));
    }

    private static final class JNIMembers {
        final UnmodifiableEconomicMap<String, HostMethodDesc> methods;
        final UnmodifiableEconomicMap<String, HostMethodDesc> staticMethods;

        JNIMembers(Members members) {
            this.methods = collectJNINamedMethods(members.methods);
            this.staticMethods = collectJNINamedMethods(members.staticMethods);
        }

        private static UnmodifiableEconomicMap<String, HostMethodDesc> collectJNINamedMethods(Map<String, HostMethodDesc> methods) {
            EconomicMap<String, HostMethodDesc> jniMethods = EconomicMap.create();
            for (HostMethodDesc method : methods.values()) {
                if (method.isConstructor()) {
                    continue;
                }
                for (SingleMethod m : method.getOverloads()) {
                    assert m.isMethod();
                    jniMethods.put(HostInteropReflect.jniName((Method) m.getReflectionMethod()), m);
                }
            }
            return jniMethods;
        }
    }

    private static final class MethodsBySignature {
        final UnmodifiableEconomicMap<String, HostMethodDesc> methods;
        final UnmodifiableEconomicMap<String, HostMethodDesc> staticMethods;

        MethodsBySignature(Members members) {
            this.methods = collectMethodsBySignature(members.methods);
            this.staticMethods = collectMethodsBySignature(members.staticMethods);
        }

        private static UnmodifiableEconomicMap<String, HostMethodDesc> collectMethodsBySignature(Map<String, HostMethodDesc> methods) {
            EconomicMap<String, HostMethodDesc> methodMap = EconomicMap.create();
            for (HostMethodDesc method : methods.values()) {
                if (method.isConstructor()) {
                    continue;
                }
                for (SingleMethod m : method.getOverloads()) {
                    assert m.isMethod();
                    if (!m.isOnlyVisibleFromJniName()) {
                        methodMap.put(HostInteropReflect.toNameAndSignature((Method) m.getReflectionMethod()), m);
                    }
                }
            }
            return methodMap;
        }
    }

    private Members getMembers() {
        Members m = members;
        if (m == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            synchronized (this) {
                m = members;
                if (m == null) {
                    HostClassCache localCache = getCache();
                    members = m = new Members(localCache, type);
                }
            }
        }
        return m;
    }

    private HostClassCache getCache() {
        HostClassCache localCache = this.cache.get();
        assert localCache != null : "cache was collected but should no longer be accessible";
        return localCache;
    }

    private JNIMembers getJNIMembers() {
        JNIMembers m = jniMembers;
        if (m == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            synchronized (this) {
                m = jniMembers;
                if (m == null) {
                    jniMembers = m = new JNIMembers(getMembers());
                }
            }
        }
        return m;
    }

    private MethodsBySignature getMethodsBySignature() {
        MethodsBySignature m = methodsBySignature;
        if (m == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            synchronized (this) {
                m = methodsBySignature;
                if (m == null) {
                    methodsBySignature = m = new MethodsBySignature(getMembers());
                }
            }
        }
        return m;
    }

    /**
     * Looks up a public non-static method in this class.
     *
     * @param name method name
     * @return method descriptor or {@code null} if there is no such method
     */
    private HostMethodDesc lookupMethod(String name) {
        return getMembers().methods.get(name);
    }

    /**
     * Looks up a public static method in this class.
     *
     * @param name method name
     * @return method descriptor or {@code null} if there is no such method
     */
    private HostMethodDesc lookupStaticMethod(String name) {
        return getMembers().staticMethods.get(name);
    }

    public HostMethodDesc lookupMethod(String name, boolean onlyStatic) {
        return onlyStatic ? lookupStaticMethod(name) : lookupMethod(name);
    }

    HostMethodDesc lookupMethodBySignature(String nameAndSignature, boolean onlyStatic) {
        MethodsBySignature m = getMethodsBySignature();
        return onlyStatic ? m.staticMethods.get(nameAndSignature) : m.methods.get(nameAndSignature);
    }

    public HostMethodDesc lookupMethodByJNIName(String jniName, boolean onlyStatic) {
        JNIMembers m = getJNIMembers();
        return onlyStatic ? m.staticMethods.get(jniName) : m.methods.get(jniName);
    }

    public Collection<String> getMethodNames(boolean onlyStatic, boolean includeInternal) {
        Map<String, HostMethodDesc> methods = onlyStatic ? getMembers().staticMethods : getMembers().methods;
        if (includeInternal || onlyStatic) {
            return Collections.unmodifiableCollection(methods.keySet());
        } else {
            Collection<String> methodNames = new ArrayList<>(methods.size());
            for (Map.Entry<String, HostMethodDesc> entry : methods.entrySet()) {
                if (!entry.getValue().isInternal()) {
                    methodNames.add(entry.getKey());
                }
            }
            return methodNames;
        }
    }

    /**
     * Looks up public constructor in this class.
     *
     * @return method descriptor or {@code null} if there is no public constructor
     */
    public HostMethodDesc lookupConstructor() {
        return getMembers().constructor;
    }

    /**
     * Looks up a public non-static field in this class.
     *
     * @param name field name
     * @return field or {@code null} if there is no such field
     */
    private HostFieldDesc lookupField(String name) {
        return getMembers().fields.get(name);
    }

    /**
     * Looks up a public static field in this class.
     *
     * @param name field name
     * @return field or {@code null} if there is no such field
     */
    private HostFieldDesc lookupStaticField(String name) {
        return getMembers().staticFields.get(name);
    }

    public HostFieldDesc lookupField(String name, boolean onlyStatic) {
        return onlyStatic ? lookupStaticField(name) : lookupField(name);
    }

    public Collection<String> getFieldNames(boolean onlyStatic) {
        return Collections.unmodifiableCollection((onlyStatic ? getMembers().staticFields : getMembers().fields).keySet());
    }

    public HostMethodDesc getFunctionalMethod() {
        return getMembers().functionalMethod;
    }

    public AdapterResult getAdapter(HostContext hostContext) {
        AdapterResult result = adapter;
        if (result == null) {
            result = getOrSetAdapter(hostContext);
        }
        return result;
    }

    private AdapterResult getOrSetAdapter(HostContext hostContext) {
        CompilerAsserts.neverPartOfCompilation();
        synchronized (this) {
            AdapterResult result = adapter;
            if (result == null) {
                adapter = result = HostAdapterFactory.makeAdapterClassFor(getCache(), type, hostContext.getClassloader());
            }
            return result;
        }
    }

    @Override
    public String toString() {
        return "JavaClass[" + type.getCanonicalName() + "]";
    }

}

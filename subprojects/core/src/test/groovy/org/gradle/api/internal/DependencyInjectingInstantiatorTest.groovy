/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal

import org.gradle.api.Transformer
import org.gradle.api.internal.cache.CrossBuildInMemoryCache
import org.gradle.api.reflect.ObjectInstantiationException
import org.gradle.internal.service.ServiceRegistry
import org.gradle.internal.service.UnknownServiceException
import spock.lang.Specification

import javax.inject.Inject

class DependencyInjectingInstantiatorTest extends Specification {
    def services = Mock(ServiceRegistry)
    def classGenerator = Mock(ClassGenerator)
    def instantiator = new DependencyInjectingInstantiator(classGenerator, services, new TestCache())

    def "creates instance that has default constructor"() {
        given:
        classGenerator.generate(_) >> { Class<?> c -> c }

        when:
        def result = instantiator.newInstance(HasDefaultConstructor)

        then:
        result instanceof HasDefaultConstructor
    }

    def "injects provided parameters into constructor"() {
        given:
        classGenerator.generate(_) >> { Class<?> c -> c }

        when:
        def result = instantiator.newInstance(HasInjectConstructor, "string", 12)

        then:
        result.param1 == "string"
        result.param2 == 12
    }

    def "injects missing parameters from provided service registry"() {
        given:
        classGenerator.generate(_) >> { Class<?> c -> c }
        services.get(String) >> "string"

        when:
        def result = instantiator.newInstance(HasInjectConstructor, 12)

        then:
        result.param1 == "string"
        result.param2 == 12
    }

    def "unboxes primitive types"() {
        given:
        classGenerator.generate(_) >> { Class<?> c -> c }

        when:
        def result = instantiator.newInstance(AcceptsPrimitiveTypes, 12, true)

        then:
        result.param1 == 12
        result.param2
    }

    def "constructors do not need to be public but do need to be annotated"() {
        given:
        classGenerator.generate(_) >> { Class<?> c -> c }

        expect:
        instantiator.newInstance(HasPrivateConstructor, "param") != null
    }

    def "class can be package scoped"() {
        given:
        classGenerator.generate(_) >> { Class<?> c -> c }

        expect:
        instantiator.newInstance(PackageScopedClass) != null
    }

    def "selects annotated constructor when class has multiple constructors and only one is annotated"() {
        given:
        classGenerator.generate(_) >> { Class<?> c -> c }

        when:
        def result = instantiator.newInstance(HasOneInjectConstructor, 12)

        then:
        result != null
    }

    def "wraps constructor failure"() {
        given:
        classGenerator.generate(_) >> { Class<?> c -> c }

        when:
        instantiator.newInstance(HasBrokenConstructor)

        then:
        ObjectInstantiationException e = thrown()
        e.cause == HasBrokenConstructor.failure
    }

    def "reports requested type rather than implementation type on constructor failure"() {
        given:
        classGenerator.generate(HasBrokenConstructor) >> HasBrokenConstructorSub

        when:
        instantiator.newInstance(HasBrokenConstructor)

        then:
        ObjectInstantiationException e = thrown()
        e.message == "Could not create an instance of type $HasBrokenConstructor.name."
        e.cause == HasBrokenConstructor.failure
    }

    def "fails when too many constructor parameters provided"() {
        given:
        classGenerator.generate(_) >> { Class<?> c -> c }

        when:
        instantiator.newInstance(HasOneInjectConstructor, 12, "param2")

        then:
        ObjectInstantiationException e = thrown()
        e.cause.message == "Too many parameters provided for constructor for class $HasOneInjectConstructor.name. Expected 1, received 2."
    }

    def "fails when supplied parameters cannot be used to call constructor"() {
        given:
        classGenerator.generate(_) >> { Class<?> c -> c }
        services.get(Number) >> 12

        when:
        instantiator.newInstance(HasOneInjectConstructor, new StringBuilder("string"))

        then:
        ObjectInstantiationException e = thrown()
        e.cause.message == "Unexpected parameter provided for constructor for class $HasOneInjectConstructor.name."
    }

    def "fails on missing service"() {
        given:
        def failure = new UnknownServiceException(String, "unknown")
        classGenerator.generate(_) >> { Class<?> c -> c }
        services.get(String) >> { throw failure }

        when:
        instantiator.newInstance(HasInjectConstructor, 12)

        then:
        ObjectInstantiationException e = thrown()
        e.cause == failure
    }

    def "fails when class has multiple constructors and none are annotated"() {
        given:
        classGenerator.generate(_) >> { Class<?> c -> c }

        when:
        instantiator.newInstance(HasNoInjectConstructor, new StringBuilder("param"))

        then:
        ObjectInstantiationException e = thrown()
        e.cause.message == "Class $HasNoInjectConstructor.name has no constructor that is annotated with @Inject."
    }

    def "fails when class has multiple constructor that are annotated"() {
        given:
        classGenerator.generate(_) >> { Class<?> c -> c }

        when:
        instantiator.newInstance(HasMultipleInjectConstructors, new StringBuilder("param"))

        then:
        ObjectInstantiationException e = thrown()
        e.cause.message == "Class $HasMultipleInjectConstructors.name has multiple constructors that are annotated with @Inject."
    }

    def "fails when class has non-public zero args constructor that is not annotated"() {
        given:
        classGenerator.generate(HasNonPublicNoArgsConstructor) >> HasNonPublicNoArgsConstructorSub

        when:
        instantiator.newInstance(HasNonPublicNoArgsConstructor, new StringBuilder("param"))

        then:
        ObjectInstantiationException e = thrown()
        e.cause.message == "The constructor for class $HasNonPublicNoArgsConstructor.name should be public or package protected or annotated with @Inject."
    }

    def "fails when class has public constructor with args and that is not annotated"() {
        given:
        classGenerator.generate(HasSingleConstructorWithArgsAndNoAnnotation) >> HasSingleConstructorWithArgsAndNoAnnotationSub

        when:
        instantiator.newInstance(HasSingleConstructorWithArgsAndNoAnnotation, "param")

        then:
        ObjectInstantiationException e = thrown()
        e.cause.message == "The constructor for class $HasSingleConstructorWithArgsAndNoAnnotation.name should be annotated with @Inject."
    }

    static class TestCache implements CrossBuildInMemoryCache<Class<?>, DependencyInjectingInstantiator.CachedConstructor> {
        @Override
        DependencyInjectingInstantiator.CachedConstructor get(Class<?> key) {
            return null;
        }

        @Override
        DependencyInjectingInstantiator.CachedConstructor get(Class<?> key, Transformer<DependencyInjectingInstantiator.CachedConstructor, Class<?>> factory) {
            return factory.transform(key)
        }

        @Override
        void put(Class<?> key, DependencyInjectingInstantiator.CachedConstructor value) {
        }

        @Override
        void clear() {
        }
    }

    public static class HasDefaultConstructor {
    }

    public static class HasNonPublicNoArgsConstructor {
        protected HasNonPublicNoArgsConstructor() {
        }
    }

    public static class HasNonPublicNoArgsConstructorSub extends HasNonPublicNoArgsConstructor {
        protected HasNonPublicNoArgsConstructorSub() {
        }
    }

    public static class HasSingleConstructorWithArgsAndNoAnnotation {
        HasSingleConstructorWithArgsAndNoAnnotation(String arg) {
        }
    }

    public static class HasSingleConstructorWithArgsAndNoAnnotationSub extends HasSingleConstructorWithArgsAndNoAnnotation {
        HasSingleConstructorWithArgsAndNoAnnotationSub(String arg) {
            super(arg)
        }
    }

    public static class HasBrokenConstructor {
        static def failure = new RuntimeException()

        HasBrokenConstructor() {
            throw failure
        }
    }

    public static class HasBrokenConstructorSub extends HasBrokenConstructor {
    }

    public static class HasInjectConstructor {
        String param1
        Number param2

        @Inject
        HasInjectConstructor(String param1, Number param2) {
            this.param1 = param1
            this.param2 = param2
        }
    }

    public static class AcceptsPrimitiveTypes {
        int param1
        boolean param2

        @Inject
        AcceptsPrimitiveTypes(int param1, boolean param2) {
            this.param1 = param1
            this.param2 = param2
        }
    }

    public static class HasOneInjectConstructor {
        HasOneInjectConstructor(String param1) {
        }

        @Inject
        HasOneInjectConstructor(Number param1) {
        }
    }

    public static class HasNoInjectConstructor {
        HasNoInjectConstructor(String param1) {
        }

        HasNoInjectConstructor(Number param1) {
            throw new AssertionError()
        }

        HasNoInjectConstructor() {
            throw new AssertionError()
        }
    }

    public static class HasPrivateConstructor {
        @Inject
        private HasPrivateConstructor(String param1) {
        }
    }

    public static class HasMultipleInjectConstructors {
        @Inject
        HasMultipleInjectConstructors(String param1) {
        }

        @Inject
        HasMultipleInjectConstructors(Number param1) {
            throw new AssertionError()
        }

        @Inject
        HasMultipleInjectConstructors() {
            throw new AssertionError()
        }
    }

}

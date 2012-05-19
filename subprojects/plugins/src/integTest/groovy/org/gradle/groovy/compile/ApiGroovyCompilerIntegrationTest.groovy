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

package org.gradle.groovy.compile

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.TestResources

import org.junit.Rule

import spock.lang.Issue

class ApiGroovyCompilerIntegrationTest extends AbstractIntegrationSpec {
    @Rule TestResources resources = new TestResources()

    def "canCompileAgainstGroovyClassThatDependsOnExternalClass"() {
        when:
        inDirectory(resources.dir).withTasks("test").run()

        then:
        noExceptionThrown()
    }

    def "canUseBuiltInAstTransform"() {
        when:
        inDirectory(resources.dir).withTasks("test").run()

        then:
        noExceptionThrown()
    }

    def "canUseThirdPartyAstTransform"() {
        when:
        inDirectory(resources.dir).withTasks("test").run()

        then:
        noExceptionThrown()
    }

    def "canUseAstTransformWrittenInGroovy"() {
        when:
        inDirectory(resources.dir).withTasks("test").run()

        then:
        noExceptionThrown()
    }

    // more generally, this test is about transforms that statically reference
    // a class from the Groovy (compiler) Jar that in turn references a class from another Jar
    @Issue("GRADLE-2317")
    def "canUseAstTransformThatReferencesGroovyTestCase"() {
        when:
        inDirectory(resources.dir).withTasks("test").run()

        then:
        noExceptionThrown()
    }
}

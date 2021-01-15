/*
 * Copyright 2015-2020 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nebula.plugin.publishing.publications

import groovy.transform.CompileDynamic
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.XmlProvider
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.ivy.IvyPublication
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar

/**
 * This feature is deprecated.  The paved road is for any common test harnesses to be bundled in the main source folder
 * of a separate project.  Only that project's compile and runtime configurations should be exported so that it is
 * possible to test the test harness without affecting the testCompile and testRuntime classpaths of the consumer of
 * the test harness.
 */
@Deprecated
@CompileDynamic
class TestJarPlugin implements Plugin<Project> {
    static final String FIXTURE_CONF = 'test'

    @Override
    void apply(Project project) {
        project.logger.warn('The testJar task is deprecated.  Please place common test harness code in its own project and publish separately.')

        project.plugins.withType(JavaPlugin) { // needed for source sets
            TaskProvider<Jar> testJar = project.tasks.register('testJar', Jar)
            testJar.configure(new Action<Jar>() {
                @Override
                void execute(Jar jar) {
                    jar.dependsOn project.tasks.getByName('testClasses')
                    jar.archiveClassifier.set 'tests'
                    jar.archiveExtension.set 'jar'
                    jar.from project.sourceSets.test.output
                    jar.group 'build'
                }
            })

            def fixtureConf = project.configurations.maybeCreate(FIXTURE_CONF)
            Configuration testRuntimeConf = project.configurations.getByName(JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME)
            fixtureConf.extendsFrom(testRuntimeConf)

            project.artifacts {
                test testJar
            }

            project.plugins.withType(org.gradle.api.publish.maven.plugins.MavenPublishPlugin) {
                project.publishing {
                    publications {
                        nebula(MavenPublication) {
                            artifact project.tasks.testJar

                            pom.withXml { XmlProvider xml ->
                                def root = xml.asNode()
                                def dependencies = root.dependencies ? root.dependencies[0] : root.appendNode('dependencies')

                                [project.configurations.testImplementation, project.configurations.testRuntimeOnly].each {
                                    it.dependencies.each { dep ->
                                        dependencies.appendNode('dependency').with {
                                            appendNode('groupId', dep.group)
                                            appendNode('artifactId', dep.name)
                                            appendNode('version', dep.version)
                                            appendNode('scope', 'test')
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            project.plugins.withType(org.gradle.api.publish.ivy.plugins.IvyPublishPlugin) {
                project.publishing {
                    publications {
                        nebulaIvy(IvyPublication) {
                            artifact(project.tasks.testJar) {
                                conf 'test'
                            }

                            descriptor.withXml { XmlProvider xml ->
                                def root = xml.asNode()

                                def confs = root.configurations[0]
                                if (!confs.conf.find { it.@name == 'test' }) {
                                    confs.appendNode('conf', [
                                            visibility: 'public',
                                            extends: 'runtime',
                                            name: 'test'
                                    ])
                                }

                                def dependencyList = root.dependencies[0]

                                def excludes = dependencyList.exclude
                                excludes.each {
                                    dependencyList.remove(it)
                                }

                                [project.configurations.testImplementation, project.configurations.testRuntimeOnly].each {
                                    it.dependencies.each { dep ->
                                        dependencyList.appendNode('dependency', [
                                                org: dep.group,
                                                name: dep.name,
                                                rev: dep.version,
                                                revConstraint: dep.version,
                                                conf: 'test->default'
                                        ])
                                    }
                                }

                                excludes.each {
                                    dependencyList.append(it)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

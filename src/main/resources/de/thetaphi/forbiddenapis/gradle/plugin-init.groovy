/*
 * (C) Copyright Uwe Schindler (Generics Policeman) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/** Initializes the plugin and binds it to project lifecycle. */

import java.lang.reflect.Modifier;
import org.gradle.api.plugins.JavaBasePlugin;

project.plugins.apply(JavaBasePlugin.class);

// create Extension for defaults:
def extension = project.extensions.create(FORBIDDEN_APIS_EXTENSION_NAME, CheckForbiddenApisExtension.class);
extension.with{
  signaturesFiles = project.files();
  disableClassloadingCache |= isGradleDaemon;
}
def extensionProps = CheckForbiddenApisExtension.class.declaredFields.findAll{ f -> 
  int mods = f.modifiers;
  return Modifier.isPublic(mods) && !f.synthetic && !Modifier.isStatic(mods)
}*.name;

// Create a convenience task for all checks (this does not conflict with extension, as it has higher priority in DSL):
def forbiddenTask = project.tasks.create(FORBIDDEN_APIS_TASK_NAME) {
  description = "Runs forbidden-apis checks.";
  group = JavaBasePlugin.VERIFICATION_GROUP;
}

// Define our tasks (one for each SourceSet):
project.sourceSets.all{ sourceSet ->
  def getSourceSetClassesDirs = { sourceSet.output.hasProperty('classesDirs') ? sourceSet.output.classesDirs : project.files(sourceSet.output.classesDir) }
  project.tasks.create(sourceSet.getTaskName(FORBIDDEN_APIS_TASK_NAME, null), CheckForbiddenApis.class) { task ->
    description = "Runs forbidden-apis checks on '${sourceSet.name}' classes.";
    conventionMapping.with{
      extensionProps.each{ key ->
        map(key, { extension[key] });
      }
      classesDirs = { getSourceSetClassesDirs() }
      classpath = { sourceSet.compileClasspath }
      // Gradle is buggy with it's JavaVersion enum: We use majorVersion property before Java 11 (6,7,8,9,10) and for later we use toString() to be future-proof:
      targetCompatibility = { (project.targetCompatibility?.hasProperty('java11Compatible') && project.targetCompatibility?.java11Compatible) ?
        project.targetCompatibility.toString() : project.targetCompatibility?.majorVersion }
    }
    // add dependency to compile task after evaluation, if the classesDirs collection has overlaps with our SourceSet:
    project.afterEvaluate{
      def sourceSetDirs = getSourceSetClassesDirs().files;
      if (classesDirs.any{ sourceSetDirs.contains(it) }) {
        task.dependsOn(sourceSet.output);
      }
    }
    outputs.upToDateWhen { true }
    forbiddenTask.dependsOn(task);
  }
}

// Add our task as dependency to chain
project.tasks[JavaBasePlugin.CHECK_TASK_NAME].dependsOn(forbiddenTask);

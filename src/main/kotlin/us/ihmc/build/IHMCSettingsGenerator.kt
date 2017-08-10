package us.ihmc.build

import org.apache.commons.io.FileUtils
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.initialization.IncludedBuild
import org.gradle.kotlin.dsl.closureOf
import us.ihmc.commons.exception.DefaultExceptionHandler
import us.ihmc.commons.nio.FileTools
import us.ihmc.continuousIntegration.AgileTestingTools
import java.io.File
import java.nio.file.Path

class IHMCSettingsGenerator(project: Project)
{
   init
   {
      if (project.hasProperty("disableSettingsGeneration") && project.property("disableSettingsGeneration") as String == "false")
      {
         println("[ihmc-build] [WARN] IHMCSettingsGenerator: Disabling settings.gradle generation!")
      }
      else
      {
         val generateSettingsTask = project.task("generateSettings", closureOf<Task> {
            //            for (includedBuild in project.gradle.includedBuilds)
//            {
//               dependsOn(includedBuild.task(":printDependenciesFile"))
//            }
//
//            doLast {
//               project.gradle.includedBuilds.each {
//
//                  def dependencies = settings.findTransitiveProjectDependencies (it)
//
//                  settings.generateSettingsFile(it.projectDir, dependencies)
//                  println(it.name + ": " + dependencies)
//               }
//
//               project.gradle.includedBuilds.each {
//                  settings.deleteDependenciesFile(it.projectDir)
//               }
//            }
//
//            val settingsFile = project.rootDir.resolve("settings.gradle")
//
//            println("[ihmc-build] Generating file: " + settingsFile.absolutePath)
//
//            val buildsToInclude = sortedSetOf<String>()
//
//            project.allprojects(closureOf<Project> {
//               configurations.getByName("compile").allDependencies.forEach {
//                  if (it.group.startsWith("us.ihmc"))
//                  {
//                     if (file("../" + it.name).exists())
//                     {
//                        buildsToInclude.add(it.name as String)
//                     }
//
//                     val pascalCasedName = AgileTestingTools.hyphenatedToPascalCased(it.name)
//                     if (file("../" + pascalCasedName).exists())
//                     {
//                        buildsToInclude.add(pascalCasedName)
//                     }
//                  }
//               }
//            })
   
            doLast {
               writeSettingsFileToProject(project.projectDir)
            }
         })

//         println(buildsToInclude)
//         generateSetttingsFile(buildsToInclude, settingsFile)
         
         project.getTasksByName("compileJava", false).forEach {
            it.dependsOn(generateSettingsTask)
         }
      }
      
      project.task("printDependenciesFile", closureOf<Task>
      {
         doLast {
            val dependenciesFile = project.rootDir.resolve("dependencies.txt")
            
            println("[ihmc-build] Generating file: " + dependenciesFile.absolutePath)
            
            val buildsToInclude = sortedSetOf<String>()
            
            project.allprojects(closureOf<Project> {
               configurations.getByName("compile").allDependencies.forEach {
                  if (it.group.startsWith("us.ihmc"))
                  {
                     if (file("../" + it.name).exists())
                     {
                        buildsToInclude.add(it.name as String)
                     }
                     
                     val pascalCasedName = AgileTestingTools.hyphenatedToPascalCased(it.name)
                     if (file("../" + pascalCasedName).exists())
                     {
                        buildsToInclude.add(pascalCasedName)
                     }
                  }
               }
            })
            
            println(buildsToInclude)
            
            var text = ""
            var first = 0
            buildsToInclude.forEach {
               if (first == 0)
               {
                  first = 1
               }
               else
               {
                  text += "\n"
               }
               text += "$it"
            }
            
            dependenciesFile.writeText(text)
         }
      })
      
      project.task("deleteDependenciesFile", closureOf<Task>
      {
         doLast {
            val dependenciesFile = project.rootDir.resolve("dependencies.txt")
            
            println("[ihmc-build] Deleting file: " + dependenciesFile.absolutePath)
            
            FileUtils.deleteQuietly(dependenciesFile)
         }
      })
   }
   
   fun writeSettingsFileToProject(projectDir: File)
   {
      val settingsFile = projectDir.resolve("settings.gradle")
   
      println("[ihmc-build] Generating file: " + settingsFile.absolutePath)
   
      val fileContent = IHMCSettingsGenerator::class.java.getResource("/settings.gradle").readText()
      settingsFile.writeText(fileContent)
   }
   
   fun generateSettingsFile(projectDir: File, buildsToInclude: Set<String>)
   {
      generateSetttingsFile(buildsToInclude, projectDir.resolve("settings.gradle"))
   }
   
   private fun generateSetttingsFile(buildsToInclude: Set<String>, settingsFile: File)
   {
      println("[ihmc-build] Generating file: " + settingsFile.absolutePath)
      
      var text = ""
      text += "/**" + "\n"
      text += " * THIS FILE IS AUTO-GENERATED BY THE IHMC BUILD GRADLE PLUGIN" + "\n"
      text += " * To disable auto-generation, set \"disableSettingsGeneration = false\"" + "\n"
      text += " * in the gradle.properties file." + "\n"
      text += " */" + "\n"
      text += "\n"
      text += "rootProject.name = hyphenatedName" + "\n"
      text += "\n"
      text += "println \"Evaluating \" + pascalCasedName + \" settings.gradle\"" + "\n"
      text += "\n"
      text += "Eval.me(extraSourceSets).each {" + "\n"
      text += "   new File(rootProject.projectDir, it).mkdir()" + "\n"
      text += "   include it" + "\n"
      text += "   project(\":\" + it).name = hyphenatedName + \"-\" + it" + "\n"
      text += "}" + "\n"
      
      if (!buildsToInclude.isEmpty())
      {
         text += "\n"
         text += "def includes = [" + "\n"
         buildsToInclude.forEach {
            text += "   \"../$it\",\n"
         }
         text += "]" + "\n"
         
         text += "\n"
         text += "if (startParameter.searchUpwards)" + "\n"
         text += "{" + "\n"
         text += "   includes.each { include ->" + "\n"
         text += "      if (new File(rootProject.projectDir, include).exists())" + "\n"
         text += "         includeBuild include" + "\n"
         text += "   }" + "\n"
         text += "}" + "\n"
      }
      
      settingsFile.writeText(text)
   }
   
   fun buildDependencyTreeWithTransitives()
   {
   
   }
   
   fun deleteDependenciesFile(projectDir: File)
   {
      val dependenciesFile = projectDir.resolve("dependencies.txt")
      
      println("[ihmc-build] Deleting file: " + dependenciesFile.absolutePath)
      
      FileUtils.deleteQuietly(dependenciesFile)
   }
   
   fun findTransitiveProjectDependencies(includedBuild: IncludedBuild): Set<String>
   {
      return findTransitivesRecursive(includedBuild.projectDir.toPath(), includedBuild.name, sortedSetOf<String>())
   }
   
   private fun findTransitivesRecursive(projectDir: Path, name: String, set: java.util.TreeSet<String>): java.util.TreeSet<String>
   {
      val lines = FileTools.readAllLines(projectDir.resolve("dependencies.txt"), DefaultExceptionHandler.PRINT_MESSAGE)
      
      for (line in lines)
      {
         if (!set.contains(line))
         {
            set.add(line)
            
            findTransitivesRecursive(projectDir.parent.resolve(line), line, set)
         }
      }
      
      return set
   }
}
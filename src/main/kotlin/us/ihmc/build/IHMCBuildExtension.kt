package us.ihmc.build

import groovy.util.Eval
import org.apache.commons.io.FileUtils
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.UnknownProjectException
import org.gradle.api.XmlProvider
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.closureOf
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.extra
import org.jfrog.artifactory.client.Artifactory
import org.jfrog.artifactory.client.ArtifactoryClientBuilder
import org.jfrog.artifactory.client.model.RepoPath
import us.ihmc.commons.nio.FileTools
import us.ihmc.continuousIntegration.AgileTestingTools
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*

open class IHMCBuildExtension(val project: Project)
{
   var productGroup = "unset.group"
   var productVersion = "UNSET-VERSION"
   var vcsUrl: String = "unset_vcs_url"
   var openSource: Boolean = false
   var licenseURL: String = "proprietary"
   var licenseName: String = "Proprietary"
   var companyName: String = "IHMC"
   var maintainer: String = "Rosie (dragon_ryderz@ihmc.us)"
   
   private val bintrayUser: String
   private val bintrayApiKey: String
   private val artifactoryUsername: String
   private val artifactoryPassword: String
   
   private val publishModeProperty: String
   private val hyphenatedNameProperty: String
   private val bambooBranchNameProperty: String
   private val bambooBuildNumberProperty: String
   private val bambooParentBuildKeyProperty: String
   private val groupDependencyVersionProperty: String
   
   init
   {
      bintrayUser = setupPropertyWithDefault("bintray_user", "unset_user")
      bintrayApiKey = setupPropertyWithDefault("bintray_key", "unset_api_key")
      artifactoryUsername = setupPropertyWithDefault("artifactoryUsername", "unset_username")
      artifactoryPassword = setupPropertyWithDefault("artifactoryPassword", "unset_password")
      
      groupDependencyVersionProperty = setupPropertyWithDefault("groupDependencyVersion", "SNAPSHOT-LATEST")
      publishModeProperty = setupPropertyWithDefault("publishMode", "SNAPSHOT")
      hyphenatedNameProperty = setupPropertyWithDefault("hyphenatedName", "")
      bambooBuildNumberProperty = setupPropertyWithDefault("bambooBuildNumber", "0")
      bambooBranchNameProperty = setupPropertyWithDefault("bambooBranchName", "")
      bambooParentBuildKeyProperty = setupPropertyWithDefault("bambooParentBuildKey", "")
   }
   
   fun setupPropertyWithDefault(propertyName: String, defaultValue: String): String
   {
      if (project.hasProperty(propertyName) && !(project.property(propertyName) as String).startsWith("$"))
      {
         return project.property(propertyName) as String
      }
      else
      {
         if (propertyName == "artifactoryUsername" || propertyName == "artifactoryPassword")
            printQuiet("Please set artifactoryUsername and artifactoryPassword in /path/to/user/.gradle/gradle.properties.")
         if (propertyName == "bintray_user" || propertyName == "bintray_key")
            printQuiet("Please set bintray_user and bintray_key in /path/to/user/.gradle/gradle.properties.")
         
         printQuiet("No value found for $propertyName. Using default value: $defaultValue")
         project.extra.set(propertyName, defaultValue)
         return defaultValue
      }
   }
   
   fun loadProductProperties(propertiesFilePath: String)
   {
      val properties = Properties()
      properties.load(FileInputStream(project.projectDir.toPath().resolve(propertiesFilePath).toFile()))
      for (property in properties)
      {
         if (property.key as String == "group")
         {
            productGroup = property.value as String
            printQuiet("Loaded group: " + productGroup)
         }
         if (property.key as String == "version")
         {
            productVersion = property.value as String
            printQuiet("Loaded version: " + productVersion)
         }
         if (property.key as String == "vcsUrl")
         {
            vcsUrl = property.value as String
            printQuiet("Loaded vcsUrl: " + vcsUrl)
         }
         if (property.key as String == "openSource")
         {
            openSource = Eval.me(property.value as String) as Boolean
            printQuiet("Loaded openSource: " + openSource)
         }
      }
   }
   
   fun configureDependencyResolution()
   {
      configureGroupDependencyVersion()
      
      project.allprojects {
         (this as Project).run {
            addIHMCMavenRepositories()
            addThirdPartyMavenRepositories()
            addWorldMavenRepositories()
            addLocalMavenRepository()
            
            setupJavaSourceSets()
         }
      }
      
      try
      {
         val testProject = project.project(":" + hyphenatedNameProperty + "-test")
         testProject.dependencies {
            add("compile", project)
         }
      }
      catch (e: UnknownProjectException)
      {
      }
   }
   
   fun configureGroupDependencyVersion()
   {
      if (groupDependencyVersionProperty.startsWith("SNAPSHOT-BAMBOO"))
      {
         var groupDependencyVersion = "SNAPSHOT"
         
         if (!bambooParentBuildKeyProperty.isEmpty())
         {
            if (!bambooBranchNameProperty.isEmpty() && bambooBranchNameProperty != "develop")
            {
               groupDependencyVersion += "-$bambooBranchNameProperty"
            }
            groupDependencyVersion += "-" + bambooParentBuildKeyProperty.split("-").last()
         }
         else
         {
            groupDependencyVersion += "-LATEST"
         }
         
         printQuiet("Altering groupDependencyVersion from SNAPSHOT-BAMBOO to $groupDependencyVersion")
         project.setProperty("groupDependencyVersion", groupDependencyVersion)
      }
   }
   
   fun repository(url: String)
   {
      project.allprojects {
         (this as Project).run {
            repositories.run {
               maven {}.url = uri(url)
            }
         }
      }
   }
   
   fun configurePublications()
   {
      if (openSource)
      {
         licenseURL = "http://www.apache.org/licenses/LICENSE-2.0.txt"
         licenseName = "Apache License, Version 2.0"
      }
      
      project.allprojects {
         (this as Project).run {
            group = productGroup
            version = productVersion
            
            if (publishModeProperty == "SNAPSHOT")
            {
               version = getSnapshotVersion(version as String)
            }
            
            configureJarManifest(maintainer, companyName, licenseURL)
            
            if (publishModeProperty == "SNAPSHOT")
            {
               declareArtifactory("snapshots")
            }
            else if (publishModeProperty == "STABLE")
            {
               if (openSource)
               {
                  declareBintray()
               }
               else
               {
                  declareArtifactory("proprietary")
               }
            }
            
            val java = convention.getPlugin(JavaPluginConvention::class.java)
            
            declarePublication(name, configurations.getByName("compile"), java.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME))
         }
      }
   }
   
   fun Project.setupJavaSourceSets()
   {
      val java = convention.getPlugin(JavaPluginConvention::class.java)
      
      java.sourceCompatibility = JavaVersion.VERSION_1_8
      java.targetCompatibility = JavaVersion.VERSION_1_8
      
      java.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).java.setSrcDirs(setOf(file("src")))
      java.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).resources.setSrcDirs(setOf(file("src")))
      java.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).resources.setSrcDirs(setOf(file("resources")))
      
      if (hasProperty("useLegacySourceSets") && property("useLegacySourceSets") == "true")
      {
         if (hasProperty("extraSourceSets"))
         {
            val extraSourceSets = Eval.me(project.property("extraSourceSets") as String) as ArrayList<String>
            
            for (extraSourceSet in extraSourceSets)
            {
               if (extraSourceSet == "test")
               {
                  java.sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME).java.setSrcDirs(setOf(file("test/src")))
                  java.sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME).resources.setSrcDirs(setOf(file("test/src")))
                  java.sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME).resources.setSrcDirs(setOf(file("test/resources")))
               }
               else
               {
                  java.sourceSets.create(extraSourceSet)
                  java.sourceSets.getByName(extraSourceSet).java.setSrcDirs(setOf(file("$extraSourceSet/src")))
                  java.sourceSets.getByName(extraSourceSet).resources.setSrcDirs(setOf(file("$extraSourceSet/src")))
                  java.sourceSets.getByName(extraSourceSet).resources.setSrcDirs(setOf(file("$extraSourceSet/resources")))
               }
            }
         }
      }
   }
   
   fun Project.addIHMCMavenRepositories()
   {
      repositories.run {
         maven {}.url = uri("http://dl.bintray.com/ihmcrobotics/maven-vendor")
         maven {}.url = uri("http://dl.bintray.com/ihmcrobotics/maven-release")
         maven {}.url = uri("https://artifactory.ihmc.us/artifactory/releases/")
         maven {}.url = uri("https://artifactory.ihmc.us/artifactory/thirdparty/")
         maven {}.url = uri("https://artifactory.ihmc.us/artifactory/snapshots/")
         if (!openSource)
         {
            maven {}.url = uri("https://artifactory.ihmc.us/artifactory/proprietary/")
         }
      }
   }
   
   fun Project.addThirdPartyMavenRepositories()
   {
      repositories.run {
         maven {}.url = uri("http://clojars.org/repo/")
         maven {}.url = uri("https://github.com/rosjava/rosjava_mvn_repo/raw/master")
         maven {}.url = uri("https://oss.sonatype.org/content/repositories/snapshots")
      }
   }
   
   fun Project.addWorldMavenRepositories()
   {
      repositories.run {
         jcenter()
         mavenCentral()
      }
   }
   
   fun Project.addLocalMavenRepository()
   {
      repositories.mavenLocal()
   }
   
   fun getSnapshotVersion(version: String): String
   {
      var snapshotVersion = version + "-SNAPSHOT"
      
      if (!bambooBranchNameProperty.isEmpty() && bambooBranchNameProperty != "develop")
      {
         snapshotVersion += "-$bambooBranchNameProperty"
      }
      
      if (!bambooParentBuildKeyProperty.isEmpty())
      {
         snapshotVersion += "-" + bambooParentBuildKeyProperty.split("-").last()
      }
      else
      {
         snapshotVersion += "-$bambooBuildNumberProperty"
      }
      
      return snapshotVersion
   }
   
   fun getNightlyVersion(version: String): String
   {
      return version + "-NIGHTLY-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"))
   }
   
   internal fun getBuildVersion(groupId: String, artifactId: String, groupDependencyVersion: String): String
   {
      val buildVersion: String;
      if (groupDependencyVersion.startsWith("STABLE"))
      {
         val firstDash: Int = groupDependencyVersion.indexOf("-");
         if (firstDash > 0)
         {
            buildVersion = groupDependencyVersion.substring(firstDash + 1);
         }
         else
         {
            printQuiet("Incorrect syntax for dependencyMode: $groupDependencyVersion should be of the form STABLE-[version]")
            printQuiet("Setting buildVersion to 'error'")
            buildVersion = "error"
         }
      }
      else if (groupDependencyVersion.endsWith("-LATEST"))
      {
         buildVersion = latestVersionFromArtifactory(artifactId, groupDependencyVersion, "snapshots")
      }
      else if (groupDependencyVersion.contains("SNAPSHOT"))
      {
         buildVersion = latestVersionFromArtifactory(artifactId, groupDependencyVersion, "snapshots")
      }
      else
      {
         buildVersion = groupDependencyVersion
      }
      
      return buildVersion;
   }
   
   fun latestVersionFromArtifactory(artifactId: String, groupDependencyVersion: String, repository: String): String
   {
      val builder: ArtifactoryClientBuilder = ArtifactoryClientBuilder.create()
      builder.url = "https://artifactory.ihmc.us/artifactory"
      builder.username = artifactoryUsername
      builder.password = artifactoryPassword
      val artifactory: Artifactory = builder.build()
      val snapshots: List<RepoPath> = artifactory.searches().artifactsByGavc().repositories(repository).groupId(productGroup).artifactId(artifactId).doSearch()
      
      var groupDependencyVersionWithoutBuildNumber = groupDependencyVersion.substring(0, groupDependencyVersion.lastIndexOf("-"))
      var latestVersion: String = "error"
      var latestBuildNumber: Int = -1
      for (repoPath in snapshots)
      {
         if (repoPath.itemPath.contains(groupDependencyVersionWithoutBuildNumber))
         {
            if (repoPath.itemPath.endsWith("sources.jar") || repoPath.itemPath.endsWith(".pom"))
            {
               continue
            }
            
            val version: String = itemPathToVersion(repoPath.itemPath, artifactId)
            val buildNumber: Int = buildNumber(version)
            
            // Found exact match
            if (version.endsWith(groupDependencyVersion) && !groupDependencyVersion.endsWith("-LATEST"))
            {
               return version
            }
            
            if (version.contains(groupDependencyVersionWithoutBuildNumber))
            {
               if (latestVersion == "error")
               {
                  latestVersion = version
                  latestBuildNumber = buildNumber
               }
               else if (buildNumber > latestBuildNumber)
               {
                  latestVersion = version
                  latestBuildNumber = buildNumber
               }
            }
         }
      }
      
      return latestVersion
   }
   
   private fun buildNumber(version: String): Int
   {
      return Integer.parseInt(version.split("-").last())
   }
   
   private fun itemPathToVersion(itemPath: String, artifactId: String): String
   {
      val split: List<String> = itemPath.split("/")
      val artifact: String = split[split.size - 1]
      val withoutDotJar: String = artifact.split(".jar")[0]
      val version: String = withoutDotJar.substring(artifactId.length + 1)
      
      return version
   }
   
   private fun Project.configureJarManifest(maintainer: String, companyName: String, licenseURL: String)
   {
      tasks.getByName("jar") {
         (this as Jar).run {
            manifest.attributes.apply {
               put("Created-By", maintainer)
               put("Implementation-Title", name)
               put("Implementation-Version", version)
               put("Implementation-Vendor", companyName)
               
               put("Bundle-Name", name)
               put("Bundle-Version", version)
               put("Bundle-License", licenseURL)
               put("Bundle-Vendor", companyName)
            }
         }
      }
   }
   
   fun Project.declareArtifactory(repoName: String)
   {
      val publishing = extensions.getByType(PublishingExtension::class.java)
      publishing.repositories.maven(closureOf<MavenArtifactRepository> {
         name = "Artifactory"
         url = uri("https://artifactory.ihmc.us/artifactory/" + repoName)
         credentials.username = artifactoryUsername
         credentials.password = artifactoryPassword
      })
   }
   
   fun Project.declareBintray()
   {
      val publishing = extensions.getByType(PublishingExtension::class.java)
      publishing.repositories.maven(closureOf<MavenArtifactRepository> {
         name = "BintrayRelease"
         url = uri("https://api.bintray.com/maven/ihmcrobotics/maven-release/" + name)
         credentials.username = bintrayUser
         credentials.password = bintrayApiKey
      })
   }
   
   private fun Project.declarePublication(artifactName: String, configuration: Configuration, sourceSet: SourceSet)
   {
      val publishing = extensions.getByType(PublishingExtension::class.java)
      val publication = publishing.publications.create(sourceSet.name.capitalize(), MavenPublication::class.java)
      publication.groupId = group as String
      publication.artifactId = artifactName
      publication.version = version as String
      
      publication.pom.withXml() {
         (this as XmlProvider).run {
            val dependenciesNode = asNode().appendNode("dependencies")
            
            configuration.allDependencies.forEach {
               if (it.name != "unspecified")
               {
                  val dependencyNode = dependenciesNode.appendNode("dependency")
                  dependencyNode.appendNode("groupId", it.group)
                  dependencyNode.appendNode("artifactId", it.name)
                  dependencyNode.appendNode("version", it.version)
               }
            }
            
            asNode().appendNode("name", name)
            asNode().appendNode("url", vcsUrl)
            val licensesNode = asNode().appendNode("licenses")
            
            val licenseNode = licensesNode.appendNode("license")
            licenseNode.appendNode("name", licenseName)
            licenseNode.appendNode("url", licenseURL)
            licenseNode.appendNode("distribution", "repo")
         }
      }
      
      publication.artifact(task(mapOf("type" to Jar::class.java), sourceSet.name + "ClassesJar", closureOf<Jar> {
         from(sourceSet.output)
      }))
      
      publication.artifact(task(mapOf("type" to Jar::class.java), sourceSet.name + "SourcesJar", closureOf<Jar> {
         from(sourceSet.allJava)
         classifier = "sources"
      }))
   }
   
   /**
    * Used for artifact-test-runner to keep easy Bamboo configuration.
    * Job names are pascal cased on Bamboo and use this method to
    * resolve their hyphenated artifact counterparts.
    */
   fun convertJobNameToHyphenatedName(jobName: String): String
   {
      return AgileTestingTools.pascalCasedToHyphenatedWithoutJob(jobName)
   }
   
   fun printQuiet(message: Any)
   {
      project.logger.quiet("[ihmc-build] " + message)
   }
   
   fun printInfo(message: Any)
   {
      project.logger.info("[ihmc-build] " + message)
   }
   
   /**
    * Temporary tool for converting projects to new folder structure quickly.
    */
   fun copyTestsForProject(projectDir: File, sourceFolder: String)
   {
      val subprojectFolder = projectDir.toPath().resolve(sourceFolder)
      if (Files.exists(subprojectFolder))
      {
         val testSrcFolder = subprojectFolder.resolve("src")
         val testSrcUsFolder = testSrcFolder.resolve("us")
         val testUsFolder = subprojectFolder.resolve("us")
         
         if (Files.exists(testUsFolder))
         {
            printQuiet(testSrcFolder)
            printQuiet(testSrcUsFolder)
            printQuiet(testUsFolder)
            
            FileTools.deleteQuietly(testSrcUsFolder)
            
            try
            {
               FileUtils.copyDirectory(testUsFolder.toFile(), testSrcUsFolder.toFile())
            }
            catch (e: Exception)
            {
               printQuiet("Failed: " + e.printStackTrace())
            }
         }
      }
   }
   
   /**
    * Temporary tool for converting projects to new folder structure quickly.
    */
   fun deleteTestsFromProject(projectDir: File, sourceFolder: String)
   {
      val subprojectFolder = projectDir.toPath().resolve(sourceFolder)
      if (Files.exists(subprojectFolder))
      {
         val testSrcFolder = subprojectFolder.resolve("src")
         val testSrcUsFolder = testSrcFolder.resolve("us")
         val testUsFolder = subprojectFolder.resolve("us")
         
         if (Files.exists(testUsFolder))
         {
            printQuiet(testSrcFolder)
            printQuiet(testSrcUsFolder)
            printQuiet(testUsFolder)
            
            FileTools.deleteQuietly(testSrcUsFolder)
         }
      }
   }
}
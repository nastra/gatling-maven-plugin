
/*
 * Copyright 2011-2020 GatlingCorp (https://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.mojo;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.util.SelectorUtils;
import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.commons.FileUtilsV2_2;

@Execute(phase = LifecyclePhase.TEST_COMPILE)
@Mojo(
    name = "enterprisePackage",
    defaultPhase = LifecyclePhase.PACKAGE,
    requiresDependencyResolution = ResolutionScope.TEST)
public class EnterprisePackageMojo extends AbstractMojo {

  private static final String[] ALWAYS_EXCLUDES =
      new String[] {
        "META-INF/LICENSE",
        "META-INF/MANIFEST.MF",
        "META-INF/versions/**",
        "META-INF/maven/**",
        "*.SF",
        "*.DSA",
        "*.RSA"
      };

  private static String GATLING_GROUP_ID = "io.gatling";
  private static String GATLING_HIGHCHARTS_GROUP_ID = "io.gatling.highcharts";
  private static String GATLING_FRONTLINE_GROUP_ID = "io.gatling.frontline";
  private static Set<String> GATLING_GROUP_IDS;

  static {
    HashSet<String> groupIds = new HashSet<>();
    groupIds.add(GATLING_GROUP_ID);
    groupIds.add(GATLING_HIGHCHARTS_GROUP_ID);
    groupIds.add(GATLING_FRONTLINE_GROUP_ID);
    GATLING_GROUP_IDS = Collections.unmodifiableSet(groupIds);
  }

  @Component private RepositorySystem repository;

  @Component private MavenProjectHelper projectHelper;

  @Parameter(defaultValue = "${project}", readonly = true)
  private MavenProject project;

  @Parameter(defaultValue = "${session}", readonly = true)
  private MavenSession session;

  @Parameter private String[] excludes;

  @Parameter(defaultValue = "shaded")
  private String shadedClassifier;

  @Parameter(defaultValue = "${project.build.directory}", readonly = true)
  private File targetPath;

  private Set<Artifact> nonGatlingDependencies(Artifact artifact) {
    if (artifact == null) {
      return Collections.emptySet();
    }

    return resolveTransitively(artifact).stream()
        .filter(art -> !GATLING_GROUP_IDS.contains(art.getGroupId()))
        .collect(Collectors.toSet());
  }

  private void deprecatedFrontLineMavenPluginWarning() throws MojoFailureException {}

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    Set<Artifact> allDeps = project.getArtifacts();

    EnterpriseUtil.failOnLegacyFrontLinePlugin(project);

    Artifact gatlingApp = findByGroupIdAndArtifactId(allDeps, GATLING_GROUP_ID, "gatling-app");
    Artifact gatlingChartsHighcharts =
        findByGroupIdAndArtifactId(
            allDeps, GATLING_HIGHCHARTS_GROUP_ID, "gatling-charts-highcharts");
    Artifact frontlineProbe =
        findByGroupIdAndArtifactId(allDeps, GATLING_FRONTLINE_GROUP_ID, "frontline-probe");

    if (gatlingApp == null) {
      throw new MojoExecutionException(
          "Couldn't find io.gatling:gatling-app in project dependencies");
    }

    Set<Artifact> gatlingDependencies = new HashSet<>();
    gatlingDependencies.addAll(nonGatlingDependencies(gatlingApp));
    gatlingDependencies.addAll(nonGatlingDependencies(gatlingChartsHighcharts));
    gatlingDependencies.addAll(nonGatlingDependencies(frontlineProbe));

    Set<Artifact> filteredDeps =
        allDeps.stream()
            .filter(
                artifact ->
                    !GATLING_GROUP_IDS.contains(artifact.getGroupId())
                        && !(artifact.getGroupId().equals("io.netty")
                            && artifact.getArtifactId().equals("netty-all"))
                        && artifactNotIn(artifact, gatlingDependencies))
            .collect(Collectors.toSet());

    File workingDir;
    try {
      workingDir = Files.createTempDirectory("frontline").toFile();
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to create temp dir", e);
    }

    // extract dep jars
    for (Artifact artifact : filteredDeps) {
      ZipUtil.unpack(artifact.getFile(), workingDir, name -> exclude(name) ? null : name);
    }

    // copy compiled classes
    File outputDirectory = new File(project.getBuild().getOutputDirectory());
    Path outputDirectoryPath = outputDirectory.toPath();
    File testOutputDirectory = new File(project.getBuild().getTestOutputDirectory());
    Path testOutputDirectoryPath = testOutputDirectory.toPath();

    try {
      if (outputDirectory.exists()) {
        FileUtilsV2_2.copyDirectory(
            outputDirectory,
            workingDir,
            pathname -> !exclude(outputDirectoryPath.relativize(pathname.toPath()).toString()),
            false);
      }
      if (testOutputDirectory.exists()) {
        FileUtilsV2_2.copyDirectory(
            testOutputDirectory,
            workingDir,
            pathname -> !exclude(testOutputDirectoryPath.relativize(pathname.toPath()).toString()),
            false);
      }
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to copy compiled classes", e);
    }

    // generate META-INF directory
    File metaInfDir = new File(workingDir, "META-INF");
    metaInfDir.mkdirs();

    // generate maven files directory
    File mavenDir =
        new File(
            new File(new File(metaInfDir, "maven"), project.getGroupId()), project.getArtifactId());
    mavenDir.mkdirs();

    // generate pom.properties
    try (FileWriter fw = new FileWriter(new File(mavenDir, "pom.properties"))) {
      fw.write("groupId=" + project.getGroupId() + "\n");
      fw.write("artifactId=" + project.getArtifactId() + "\n");
      fw.write("version=" + project.getVersion() + "\n");
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to generate pom.properties", e);
    }

    // generate pom.xml
    try (FileWriter fw = new FileWriter(new File(mavenDir, "pom.xml"))) {
      fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + "\n");
      fw.write(
          "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
              + "\n");
      fw.write(
          "    xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">"
              + "\n");
      fw.write("    <modelVersion>4.0.0</modelVersion>" + "\n");
      fw.write("    <groupId>" + project.getGroupId() + "</groupId>" + "\n");
      fw.write("    <artifactId>" + project.getArtifactId() + "</artifactId>" + "\n");
      fw.write("    <version>" + project.getVersion() + "</version>" + "\n");
      fw.write("</project>");
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to generate pom.properties", e);
    }

    // generate fake manifest
    File manifest = new File(metaInfDir, "MANIFEST.MF");

    try (FileWriter fw = new FileWriter(manifest)) {
      fw.write("Manifest-Version: 1.0\n");
      fw.write("Implementation-Title: " + project.getArtifactId() + "\n");
      fw.write("Implementation-Version: " + project.getVersion() + "\n");
      fw.write("Implementation-Vendor: " + project.getGroupId() + "\n");
      fw.write("Specification-Vendor: GatlingCorp\n");
      fw.write("Gatling-Version: " + gatlingApp.getVersion() + "\n");
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to generate manifest", e);
    }

    File shaded = EnterpriseUtil.shadedArtifactFile(project, targetPath, shadedClassifier);

    // generate jar
    getLog().info("Generating Gatling Enterprise package " + shaded);
    ZipUtil.pack(workingDir, shaded);

    // attach jar so it can be deployed
    projectHelper.attachArtifact(project, "jar", shadedClassifier, shaded);

    try {
      FileUtilsV2_2.deleteDirectory(workingDir);
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to delete working directory " + workingDir, e);
    }
  }

  private boolean exclude(String name) {
    for (String pattern : ALWAYS_EXCLUDES) {
      if (SelectorUtils.match(pattern, name, false)) {
        getLog().info("Excluding file " + name);
        return true;
      }
    }
    if (excludes != null) {
      for (String pattern : excludes) {
        if (SelectorUtils.match(pattern, name, false)) {
          getLog().info("Excluding file " + name);
          return true;
        }
      }
    }
    return false;
  }

  private Set<Artifact> resolveTransitively(Artifact artifact) {
    ArtifactResolutionRequest request =
        new ArtifactResolutionRequest()
            .setArtifact(artifact)
            .setResolveRoot(true)
            .setResolveTransitively(true)
            .setServers(session.getRequest().getServers())
            .setMirrors(session.getRequest().getMirrors())
            .setProxies(session.getRequest().getProxies())
            .setLocalRepository(session.getLocalRepository())
            .setRemoteRepositories(session.getCurrentProject().getRemoteArtifactRepositories());
    return repository.resolve(request).getArtifacts();
  }

  private static boolean artifactNotIn(Artifact target, Set<Artifact> artifacts) {
    return findByGroupIdAndArtifactId(artifacts, target.getGroupId(), target.getArtifactId())
        == null;
  }

  private static Artifact findByGroupIdAndArtifactId(
      Set<Artifact> artifacts, String groupId, String artifactId) {
    for (Artifact artifact : artifacts) {
      if (artifact.getGroupId().equals(groupId) && artifact.getArtifactId().equals(artifactId)) {
        return artifact;
      }
    }
    return null;
  }
}

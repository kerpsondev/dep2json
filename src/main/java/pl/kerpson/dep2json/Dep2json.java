package pl.kerpson.dep2json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;

@Mojo(
    name = "generate-dependencies",
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true
)
public class Dep2json extends AbstractMojo {

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  private MavenProject project;

  @Parameter(defaultValue = "${session}", required = true, readonly = true)
  private MavenSession session;

  @Component
  private DependencyGraphBuilder dependencyGraphBuilder;

  @Parameter(property = "outputFile", defaultValue = "${project.build.directory}/dependencies.json")
  private File outputFile;

  @Parameter(property = "includeDependencies")
  private String includeDependencies;

  private Set<String> includeDependenciesSet;
  private Log log;

  @Override
  public void execute() {
    this.log = getLog();
    try {
      this.initializeIncludeDependencies();

      List<DependencyInfo> filteredDependencies = this.getAllDependenciesWithTransitives();

      File outputDir = outputFile.getParentFile();
      if (!outputDir.exists()) {
        outputDir.mkdirs();
      }

      this.writeDependenciesToJson(filteredDependencies);

    } catch (Exception exception) {
      log.error("Error in dep2json plugin", exception);
    }
  }

  private List<DependencyInfo> getAllDependenciesWithTransitives() throws DependencyGraphBuilderException {
    List<DependencyInfo> result = new ArrayList<>();
    Set<String> processedArtifacts = new HashSet<>();
    MavenProject rootProject = session.getTopLevelProject();

    this.processProjectWithTransitives(rootProject, result, processedArtifacts);

    for (MavenProject module : rootProject.getCollectedProjects()) {
      if (!module.equals(rootProject)) {
        this.processProjectWithTransitives(module, result, processedArtifacts);
      }
    }

    return result;
  }

  private void processProjectWithTransitives(
      MavenProject project,
      List<DependencyInfo> dependencies,
      Set<String> processedArtifacts
  ) throws DependencyGraphBuilderException {
    ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
    buildingRequest.setProject(project);

    DependencyNode rootNode = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null);

    for (DependencyNode child : rootNode.getChildren()) {
      if (this.shouldIncludeDependency(child.getArtifact())) {
        // Include this dependency and all its transitives
        this.collectDependenciesRecursively(child, dependencies, processedArtifacts);
      }
    }
  }

  private void collectDependenciesRecursively(
      DependencyNode node,
      List<DependencyInfo> dependencies,
      Set<String> processedArtifacts
  ) {
    Artifact artifact = node.getArtifact();
    String artifactKey = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();

    if (!processedArtifacts.contains(artifactKey)) {
      DependencyInfo dependencyInfo = new DependencyInfo(
          artifact.getGroupId(),
          artifact.getArtifactId(),
          artifact.getVersion()
      );

      dependencies.add(dependencyInfo);
      processedArtifacts.add(artifactKey);

      log.debug("Added dependency: " + artifactKey);
    }

    for (DependencyNode child : node.getChildren()) {
      this.collectDependenciesRecursively(child, dependencies, processedArtifacts);
    }
  }

  private void initializeIncludeDependencies() {
    includeDependenciesSet = new HashSet<>();

    if (includeDependencies != null && !includeDependencies.trim().isEmpty()) {
      String cleaned = includeDependencies.replaceAll("\\s+", "").replaceAll("\n", "");
      String[] deps = cleaned.split(",");
      for (String dep : deps) {
        String trimmed = dep.trim();
        if (!trimmed.isEmpty()) {
          includeDependenciesSet.add(trimmed);
        }
      }
    }
  }

  private boolean shouldIncludeDependency(Artifact artifact) {
    String key = artifact.getGroupId() + ":" + artifact.getArtifactId();

    if (this.includeDependenciesSet.isEmpty()) {
      return true;
    }

    return includeDependenciesSet.contains(key);
  }

  private void writeDependenciesToJson(List<DependencyInfo> dependencies) throws IOException {
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    try (FileWriter writer = new FileWriter(outputFile)) {
      gson.toJson(dependencies, writer);
    }
    log.info("Written " + dependencies.size() + " dependencies to " + outputFile.getAbsolutePath());
  }

  public static class DependencyInfo {

    private final String groupId;
    private final String artifactId;
    private final String version;

    public static DependencyInfo of(Dependency dependency) {
      return new DependencyInfo(
          dependency.getGroupId(),
          dependency.getArtifactId(),
          dependency.getVersion()
      );
    }

    public DependencyInfo(String groupId, String artifactId, String version) {
      this.groupId = groupId;
      this.artifactId = artifactId;
      this.version = version;
    }

    public String getGroupId() {
      return groupId;
    }

    public String getArtifactId() {
      return artifactId;
    }

    public String getVersion() {
      return version;
    }
  }
}
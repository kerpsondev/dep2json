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

  @Parameter(property = "excludeDependencies")
  private String excludeDependencies;

  private Set<String> includeDependenciesSet;
  private Set<String> excludeDependenciesSet;
  private Set<String> matchedParents;
  private Log log;

  @Override
  public void execute() {
    this.log = getLog();
    try {
      this.initializeIncludeDependencies();
      this.initializeExcludeDependencies();
      this.matchedParents = new HashSet<>();

      List<DependencyInfo> filteredDependencies = this.getDependencies();

      File outputDir = outputFile.getParentFile();
      if (!outputDir.exists()) {
        outputDir.mkdirs();
      }

      this.writeDependenciesToJson(filteredDependencies);

    } catch (Exception exception) {
      log.error("Error in dep2json plugin", exception);
    }
  }

  private List<DependencyInfo> getDependencies() throws DependencyGraphBuilderException {
    List<DependencyInfo> result = new ArrayList<>();
    Set<String> processedArtifacts = new HashSet<>();
    MavenProject rootProject = session.getTopLevelProject();

    this.processProjectDependencies(rootProject, result, processedArtifacts);

    for (MavenProject module : rootProject.getCollectedProjects()) {
      if (!module.equals(rootProject)) {
        this.processProjectDependencies(module, result, processedArtifacts);
      }
    }

    return result;
  }

  private void processProjectDependencies(
      MavenProject project,
      List<DependencyInfo> dependencies,
      Set<String> processedArtifacts
  ) throws DependencyGraphBuilderException {
    ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
    buildingRequest.setProject(project);

    DependencyNode rootNode = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null);

    for (DependencyNode child : rootNode.getChildren()) {
      Artifact artifact = child.getArtifact();
      String key = artifact.getGroupId() + ":" + artifact.getArtifactId();

      if (this.includeDependenciesSet.isEmpty() || includeDependenciesSet.contains(key)) {
        matchedParents.add(key);
      }
    }

    for (DependencyNode child : rootNode.getChildren()) {
      processNode(child, dependencies, processedArtifacts, false);
    }
  }

  private void processNode(
      DependencyNode node,
      List<DependencyInfo> dependencies,
      Set<String> processedArtifacts,
      boolean isTransitive
  ) {
    Artifact artifact = node.getArtifact();
    String key = artifact.getGroupId() + ":" + artifact.getArtifactId();
    String artifactKey = key + ":" + artifact.getVersion();

    if (excludeDependenciesSet.contains(key)) {
      log.debug("Excluded dependency: " + key);
      return;
    }

    boolean shouldAdd = false;

    if (includeDependenciesSet.isEmpty()) {
      shouldAdd = true;
    } else if (matchedParents.contains(key)) {
      shouldAdd = true;
      isTransitive = false;
    } else if (isTransitive) {
      shouldAdd = true;
    }

    if (shouldAdd && !processedArtifacts.contains(artifactKey)) {
      DependencyInfo dependencyInfo = new DependencyInfo(
          artifact.getGroupId(),
          artifact.getArtifactId(),
          artifact.getVersion()
      );

      dependencies.add(dependencyInfo);
      processedArtifacts.add(artifactKey);

      log.debug("Added dependency: " + artifactKey + (isTransitive ? " (transitive)" : ""));

      if (node.getChildren() != null) {
        for (DependencyNode child : node.getChildren()) {
          processNode(child, dependencies, processedArtifacts, true);
        }
      }
    } else if (matchedParents.contains(key) && node.getChildren() != null) {
      for (DependencyNode child : node.getChildren()) {
        processNode(child, dependencies, processedArtifacts, true);
      }
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

  private void initializeExcludeDependencies() {
    excludeDependenciesSet = new HashSet<>();

    if (excludeDependencies != null && !excludeDependencies.trim().isEmpty()) {
      String cleaned = excludeDependencies.replaceAll("\\s+", "").replaceAll("\n", "");
      String[] deps = cleaned.split(",");
      for (String dep : deps) {
        String trimmed = dep.trim();
        if (!trimmed.isEmpty()) {
          excludeDependenciesSet.add(trimmed);
        }
      }
    }
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
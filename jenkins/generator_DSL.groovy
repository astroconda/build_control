// Job generator script. Uses Job-DSL plugin API.

// Third party YAML parsing class. Obtain from URL below before use.
// https://repo1.maven.org/maven2/org/yaml/snakeyaml/1.17/snakeyaml-1.17.jar
import org.yaml.snakeyaml.Yaml

def yaml = new Yaml()
def config = yaml.load(readFileFromWorkspace("manifests/${manifest_file}"))
def job_def_generation_time = new Date()


this.script = "dispatch.groovy"

this.build_control_repo = readFileFromWorkspace("VAR-build_control_repo")
this.build_control_repo = this.build_control_repo.trim()

this.build_control_branch = readFileFromWorkspace("VAR-build_control_branch")
this.build_control_branch= this.build_control_branch.trim()

this.build_control_tag = readFileFromWorkspace("VAR-build_control_tag")
this.build_control_tag = this.build_control_tag.trim()

// For each label (OS) in the list provided by the 'labels' job parameter, iterate
// over each python version provided by the 'py_versions' job parameter, to obtain
// every combination of OS and python version. Generate a separate job suite for
// each combination.
for (label in labels.trim().tokenize()) {
    for (py_version in py_versions.trim().tokenize()) {
        for (numpy_version in numpy_versions.trim().tokenize()) {

            //-----------------------------------------------------------------------
            // Create a folder to contain the jobs which are created below.

            suite_name = "${manifest_file.tokenize(".")[0]}_${label}_py${py_version}_np${numpy_version}"
            folder(suite_name) {
                description("Build suite generated: ${job_def_generation_time}\n" +
                            "build control repo: ${build_control_repo}\n" +
                            "build control branch: ${build_control_branch}\n" +
                            "build control tag: ${build_control_tag}\n" +
                            "conda version: ${conda_version}\n" +
                            "conda-build version: ${conda_build_version}\n" +
                            "utils_repo: ${utils_repo}\n" +
                            "publication_root: ${config.publication_root}")
            }

            //-----------------------------------------------------------------------
            // Generate the dispatch job that will trigger the chain of package
            // build jobs.
            pipelineJob("${suite_name}/_${script.tokenize(".")[0]}") {
                // At trigger-time, allow for setting manifest culling behavior.
                parameters {
                    booleanParam("cull_manifest",
                                 true,
                                 "Whether or not package recipes that would generate a " +
                                 "package file name that already exists in the manfest's" +
                                 " channel archive are removed from the build list.")
                }
                println("\n" +
                "script: ${this.script}\n" +
                "MANIFEST_FILE: ${manifest_file}\n" +
                "LABEL: ${label}\n" +
                "PY_VERSION: ${py_version}\n" +
                "NUMPY_VERSION: ${numpy_version}\n" +
                "BUILD_CONTROL_REPO: ${build_control_repo}\n" +
                "BUILD_CONTROL_BRANCH: ${build_control_branch}\n" +
                "BUILD_CONTROL_TAG: ${build_control_tag}\n" +
                "CONDA_VERSION: ${conda_version}\n" +
                "CONDA_BUILD_VERSION: ${conda_build_version}\n" +
                "CONDA_BASE_URL: ${conda_base_URL}\n" +
                "UTILS_REPO: ${utils_repo}\n")
                environmentVariables {
                    env("JOB_DEF_GENERATION_TIME", job_def_generation_time)
                    env("SCRIPT", this.script)
                    env("MANIFEST_FILE", manifest_file)
                    env("LABEL", label)
                    env("PY_VERSION", py_version)
                    env("NUMPY_VERSION", numpy_version)
                    env("BUILD_CONTROL_REPO", build_control_repo)
                    env("BUILD_CONTROL_BRANCH", build_control_branch)
                    env("BUILD_CONTROL_TAG", build_control_tag)
                    env("CONDA_VERSION", conda_version)
                    env("CONDA_BUILD_VERSION", conda_build_version)
                    env("CONDA_BASE_URL", conda_base_URL)
                    env("UTILS_REPO", utils_repo)
                }
                definition {
                    cps {
                        script(readFileFromWorkspace("jenkins/${this.script}"))
                        sandbox()
                    }
                }
            }


            //-----------------------------------------------------------------------
            // Generate the series of actual package building jobs.

            for(pkg in config.packages) {

                pipelineJob("${suite_name}/${pkg}") {
                    environmentVariables {
                        env("JOB_DEF_GENERATION_TIME", job_def_generation_time)
                    }
                    parameters {
                        stringParam("label",
                                    "label-DEFAULTVALUE",
                                    "The node on which to run.")
                        stringParam("build_control_repo",
                                    "build_control_repo-DEFAULTVALUE",
                                    "Repository containing the build system scripts.")
                        stringParam("build_control_branch",
                                    "build_control_branch-DEFAULTVALUE",
                                    "Branch checked out to obtain build system scripts.")
                        stringParam("build_control_tag",
                                    "build_control_tag-DEFAULTVALUE",
                                    "Tag checked out to obtain build system scripts.")
                        stringParam("py_version",
                                    "py_version-DEFAULTVALUE",
                                    "python version to use")
                        stringParam("numpy_version",
                                    "numpy_version-DEFAULTVALUE",
                                    "Version of numpy to use")
                        stringParam("parent_workspace",
                                    "parent_workspace-DEFAULTVALUE",
                                    "The workspace dir of the dispatch job")
                        stringParam("manifest_file",
                                    "manifest_file-DEFAULTVALUE",
                                    "Manifest (release) file to use for the build.")
                        stringParam("cull_manifest",
                                    "cull_manifest-DEFAULTVALUE",
                                    "Was the manifest culled as part of dispatch?")
                        stringParam("channel_URL",
                                    "channel_URL-DEFAULTVALUE",
                                    "Publication channel used for culled builds.")
                    }
                    definition {
                        cps {
                            script(readFileFromWorkspace("jenkins/package_builder.groovy"))
                            sandbox()
                        }
                    }
                } // end pipelineJob

            } //end for(pkg...

        } //end for(numpy_version
    } // end for(py_version
} // end for(label

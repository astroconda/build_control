// TODO: Is there a way to reference this local, shaded class instead of fetching it from an external source
// using @Grab?
//import org.jenkinsci.plugins.pipeline.utility.steps.shaded.org.yaml.snakeyaml.Yaml // cannot find.
@Grab('org.yaml:snakeyaml:1.17')
import org.yaml.snakeyaml.Yaml

def yaml = new Yaml()
this.manifest_data_raw = readFileFromWorkspace("manifests/${manifest_file}")
println("\n\nmanifest_data_raw:\n ${this.manifest_data_raw}")
def config = yaml.load(manifest_data_raw)

// Add delimiters so that multi-line data may be incorporated into downstream
// build jobs as an environment variable value.
this.manifest_data = ""
for (line in this.manifest_data_raw.tokenize('\n')) {
   this.manifest_data = "${this.manifest_data}${line} \\\\n"  //works
}

def job_def_generation_time = new Date()

this.script = "dispatch.groovy"

// Keep a specified number of builds (purging those older upon next
// job execution) for each independent job that is created. This value is set
// as one of the updater job's parameters.
//   Note: Since the _dispatch job is executed at the highest frequency,
// its saved build logs will not go as far back in time for a given value of
// builds_to_keep than a similar collection of kept logs for a less frequently
// built job, a slowly-moving package, for instance.
println("builds_to_keep: ${builds_to_keep}")
this.num_builds_to_keep = builds_to_keep.toInteger()

// For each label (OS) in the list provided by the 'labels' job parameter, iterate
// over each python version provided by the 'py_versions' job parameter, to obtain
// every combination of OS and python version. Generate a separate job suite for
// each combination.
for (label in labels) {
    for (py_version in py_versions) {
        for (numpy_version in numpy_versions) {
     
            //-----------------------------------------------------------------------
            // Create a folder to contain the jobs which are created below.
            suite_name = "${manifest_file.tokenize(".")[0]}_${label}_py${py_version}_np${numpy_version}"
            folder(suite_name) {
                description("Build suite generated: ${job_def_generation_time}\n" +
                            "build control repo: ${build_control_repo}\n" +
                            "build control git_ref: ${build_control_git_ref}\n" +
                            "conda installer version: ${conda_installer_version}\n" +
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
                    textParam("supp_env_vars",
                              "",
                              "List of supplemental environment variables to define " +
                              "in the build envioronment.")
                }
                logRotator {
                    numToKeep(this.num_builds_to_keep)
                }
                println("\n" +
                "script: ${this.script}\n" +
                "MANIFEST_FILE: ${manifest_file}\n" +
                "LABEL: ${label}\n" +
                "PY_VERSION: ${py_version}\n" +
                "NUMPY_VERSION: ${numpy_version}\n" +
                "BUILD_CONTROL_REPO: ${build_control_repo}\n" +
                "BUILD_CONTROL_GIT_REF: ${build_control_git_ref}\n" +
                "CONDA_INSTALLER_VERSION: ${conda_installer_version}\n" +
                "CONDA_VERSION: ${conda_version}\n" +
                "CONDA_BUILD_VERSION: ${conda_build_version}\n" +
                "CONDA_BASE_URL: ${conda_base_URL}\n" +
                "UTILS_REPO: ${utils_repo}\n")
                environmentVariables {
                    env("JOB_DEF_GENERATION_TIME", job_def_generation_time)
                    env("SCRIPT", this.script)
                    env("MANIFEST_FILE", manifest_file)
                    env("MANIFEST_DATA", this.manifest_data)
                    env("LABEL", label)
                    env("PY_VERSION", py_version)
                    env("NUMPY_VERSION", numpy_version)
                    env("BUILD_CONTROL_REPO", build_control_repo)
                    env("BUILD_CONTROL_GIT_REF", build_control_git_ref)
                    env("CONDA_INSTALLER_VERSION", conda_installer_version)
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
                        env("CONDA_BUILD_VERSION", conda_build_version)
                    }
                    logRotator {
                        numToKeep(this.num_builds_to_keep)
                    }
                    parameters {
                        stringParam("label",
                                    "label-DEFAULTVALUE",
                                    "The node on which to run.")
                        stringParam("build_control_repo",
                                    "build_control_repo-DEFAULTVALUE",
                                    "Repository containing the build system scripts.")
                        stringParam("build_control_git_ref",
                                    "build_control_git_ref-DEFAULTVALUE",
                                    "Git ref to use to obtain the build system scripts.")
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
                                    "false",
                                    "Was the manifest culled as part of dispatch?")
                        stringParam("channel_URL",
                                    "channel_URL-DEFAULTVALUE",
                                    "Publication channel used for culled builds.")
                        stringParam("use_version_pins",
                                    "false",
                                    "Whether or not to use global version pins.")
                        textParam("supp_env_vars",
                                  "",
                                  "List of supplemental environment variables to define " +
                                  "in the build environment.")
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

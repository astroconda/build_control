// Job generator script. Uses Job-DSL plugin API.

// Third party YAML parsing class. Obtain from URL below before use.
// https://repo1.maven.org/maven2/org/yaml/snakeyaml/1.17/snakeyaml-1.17.jar
import org.yaml.snakeyaml.Yaml

def yaml = new Yaml()
def config = yaml.load(readFileFromWorkspace("manifests/${manifest_file}"))


//-----------------------------------------------------------------------
// Create a folder to contain the jobs which are created below.
suite_name = "${manifest_file.tokenize(".")[0]}_${label}_py${py_version}"
folder(suite_name)


//-----------------------------------------------------------------------
// Generate the dispatch job that will trigger the chain of package
// build jobs.

pipelineJob("${suite_name}/_dispatch") {
    // At trigger-time, allow for setting manifest culling behavior.
    parameters {
        booleanParam("cull_manifest",
                     true,
                     "Whether or not package recipes that would generate a " +
                     "package file name that already exists in the manfest's" +
                     " channel archive are removed from the build list.")
    }
    println("MANIFEST_FILE = ${manifest_file}")
    println("LABEL = ${label}")
    println("PY_VERSION = ${py_version}")
    println("BUILD_CONTROL_REPO = ${build_control_repo}")
    println("CONDA_VERSION = ${conda_version}")
    println("CONDA_BUILD_VERSION = ${conda_build_version}")
    println("CONDA_BASE_URL = ${conda_base_URL}")
    println("UTILS_REPO = ${utils_repo}")
    environmentVariables {
        env("MANIFEST_FILE", manifest_file)
        env("LABEL", label)
        env("PY_VERSION", py_version)
        env("BUILD_CONTROL_REPO", build_control_repo)
        env("CONDA_VERSION", conda_version)
        env("CONDA_BUILD_VERSION", conda_build_version)
        env("CONDA_BASE_URL", conda_base_URL)
        env("UTILS_REPO", utils_repo)
    }
    definition {
        cps {
            script(readFileFromWorkspace("jenkins/dispatch.groovy"))
            sandbox()
        }
    }
}


//-----------------------------------------------------------------------
// Generate the series of actual package building jobs.

for(pkg in config.packages) {

    pipelineJob("${suite_name}/${pkg}") {
        parameters {
            stringParam("label",
                        "label-DEFAULTVALUE",
                        "The node on which to run.")
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
        }
        definition {
            cps {
                script(readFileFromWorkspace("jenkins/package_builder.groovy"))
                sandbox()
            }
        }
    } // end pipelineJob

} //end for(pkg...


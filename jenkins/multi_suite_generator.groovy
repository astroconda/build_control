import org.jenkinsci.plugins.pipeline.utility.steps.shaded.org.yaml.snakeyaml.Yaml
// Top-level pipeline job that provides parameterized machinery for
// creating one or more build job suites for use in building AstroConda
// package sets.
// Uses Job-DSL plugin.

// DSL script path within the repository obtained for this job.
this.dsl_script = "jenkins/generator_DSL.groovy"

// To sidestep not-serializable exceptions, encapsulate the YAML reading
// step in a method of its own, used below.
@NonCPS
def readYaml(data) {
  def yaml = new Yaml()
  payload = yaml.load(data)
  return payload
}

def updates = ''

node("master") {

    stage("Prep") {

        // Delete any existing job workspace directory contents.
        deleteDir()
        unstash 'build_control'
        sh "env"

        // Read update/generate YAML description.
        updates = readYaml(update_list)

        // Clone the manifests repository.
        dir ("manifests") {
            git url: manifest_repo
        }

        build_control_git_ref = git_ref.trim()

        // 'Parameters' variables are provided by the execution of the
        // generator build task with parameters. Each is populated by a
        // parameter specification at job execution time. Varaiables defined as
        // build parameters for this job are automatically available in the
        // called JobDSL script invoked below by using their base name, i.e.
        // the name here without a 'this.' prefix. Other variables are not
        // automatically available, see above.
        println("  From job config:\n" +
        "build_control_repo: ${build_control_repo}\n" +
        "build_control_git_ref: ${build_control_git_ref}\n" +
        "  Parameters:\n" +
        "conda_installer_version: ${this.conda_installer_version}\n" +
        "conda_version: ${this.conda_version}\n" +
        "conda_build_version: ${this.conda_build_version}\n" +
        "conda_base_URL: ${this.conda_base_URL}\n" +
        "utils_repo: ${this.utils_repo}\n" +
        "old_jobs_action: ${this.old_jobs_action}\n" +
        "  Other values:\n" +
        "dsl_script: ${this.dsl_script}")
    }

    // Loop over requested job suites to generate or update.
    stage("Spawn job definitions") {
        def add_params = [:]
        add_params.put('build_control_repo', build_control_repo)
        add_params.put('build_control_git_ref', build_control_git_ref)

        updates.keySet().each { key, val ->
            add_params.put('manifest_basename', key)
            add_params.put('labels', updates[key].labels)
            add_params.put('py_versions', updates[key].py_versions)
            add_params.put('numpy_versions', updates[key].numpy_versions)
            add_params.put('trigger_schedule', updates[key].trigger_schedule)
            println("Generating job suite for: ${key}")
            jobDsl targets: [this.dsl_script].join("\n"),
                   lookupStrategy: "SEED_JOB",
                   additionalParameters: add_params,
                   removeAction: "${this.old_jobs_action}"
        }
    }

}

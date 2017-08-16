// Top-level pipeline job that provides parameterized machinery for
// creating one or more build job suites for use in building AstroConda
// package sets.
// Uses Job-DSL plugin.

// Directory into which supporting libraries are stored. Gets added to
// groovy classpath definition prior to imports.
this.ldir = "libs"

// URL for the YAML support library used for accessing manifest files
yaml_lib_url = "https://repo1.maven.org/maven2/org/yaml/snakeyaml/1.17/snakeyaml-1.17.jar"

// DSL script path within the repository obtained for this job.
this.dsl_script = "jenkins/generator_DSL.groovy"


node("master") {

    stage("Prep") {

        sh "printenv"

        // Delete any existing job workspace directory contents.
        deleteDir()

        // Get the git repo and branch values used to obtain this and other
        // build_control scripts so they may be passed to the jobDSL script
        // that gets invoked at the bottom of this script. Vars harvested from
        // the gitSCM stage get written to disk here so that the jobDSL script
        // below can access them.
        // Overriding an existing parameter value does not propagate the new
        // value to the jobDSL script.

        // Both 'scm.getUserRemoteConfigs' and 'getUrl' require script approval
        build_control_repo= scm.getUserRemoteConfigs()[0].getUrl()
        sh "echo ${build_control_repo} > VAR-build_control_repo"

        // Get branch spec component after last '/' character.
        // Branch names themselves shall not have slashes in them
        // when specified in the job-suite-generator job configuration.
        // This may also describe a tag, rather than a branch.
        build_control_branch = scm.branches[0].toString().tokenize("/")[-1]
        sh "echo ${build_control_branch} > VAR-build_control_branch"

        // 'Parameters' variables are provided by the execution of the
        // generator build task with parameters. Each is populated by a
        // parameter specification at job execution time. Varaiables defined as
        // build parameters for this job are automatically available in the
        // called JobDSL script invoked below by using their base name, i.e.
        // the name here without a 'this.' prefix. Other variables are not
        // automatically available, see above.
        println("  From job config:\n" +
        "build_control_repo: ${build_control_repo}\n" +
        "build_control_branch: ${build_control_branch}\n" +
        "  Parameters:\n" +
        "manifest_file: ${this.manifest_file}\n" +
        "label: ${this.label}\n" +
        "py_version: ${this.py_version}\n" +
        "conda_version: ${this.conda_version}\n" +
        "conda_build_version: ${this.conda_build_version}\n" +
        "conda_base_URL: ${this.conda_base_URL}\n" +
        "utils_repo: ${this.utils_repo}\n" +
        "publication_root: ${this.publication_root}\n" +
        "old_jobs_action: ${this.old_jobs_action}\n" +
        "  Other values:\n" +
        "dsl_script: ${this.dsl_script}")
    }

    stage("Setup") {
        sh "mkdir -p ${this.ldir}"
        // Obtain libraries to facilitate job generation tasks.
        dir ("libs") {
            sh "curl -O ${yaml_lib_url}"
        }
        // Copy files from the implicit checkout of the build_control directory
        // (handled by the job that reads this pipeline script) into the actual
        // workspace of this job so the jobDsl call below will be able to find
        // what it needs.
        sh "cp -r ${env.WORKSPACE}@script/* ."
    }

    stage("Spawn job definition") {
        jobDsl targets: [this.dsl_script].join("\n"),
               lookupStrategy: "SEED_JOB",
               additionalClasspath: ["${this.ldir}/*.jar"].join("\n"),
               removeAction: "${this.old_jobs_action}"
    }

}

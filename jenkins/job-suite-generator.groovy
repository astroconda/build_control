// Top-level pipeline job that provides parameterized machinery for
// creating one or more build job suites for use in building AstroConda
// package sets.
// Uses Job-DSL plugin.

// Directory into which supporting libraries are stored. Gets added to
// groovy classpath definition prior to imports.
this.ldir = "libs"

// URL for the YAML support library used for accessing manifest files
// Site file cache for components that would otherwise be downloaded for each
// build. A symlink exists in the Jenkins user's home directory on the build
// master which points to the actual storage location.
site_file_cache_dir = "~/site-cache"
yaml_lib_file = "snakeyaml-1.17.jar"
yaml_lib_url_base = "https://repo1.maven.org/maven2/org/yaml/snakeyaml/1.17"

// DSL script path within the repository obtained for this job.
this.dsl_script = "jenkins/generator_DSL.groovy"


node("master") {

    stage("Prep") {

        sh "env | sort"

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
        build_control_repo = scm.getUserRemoteConfigs()[0].getUrl()
        build_control_tag = ""
        sh "echo ${build_control_repo} > VAR-build_control_repo"
        build_control_bt_spec = scm.branches[0].toString()

        // Get branch spec component after last '/' character.
        // Branch names themselves shall not have slashes in them
        // when specified in the job-suite-generator job configuration.
        // This may also describe a tag, rather than a branch.
        // Requires in-process script approval for:
        //   java.lang.String java.lang.String (.find method)
        if (build_control_bt_spec.find("tags") != null) {
            build_control_branch = "master"
            build_control_tag = build_control_bt_spec.tokenize("/")[-1]
        } else { // a branch, including */master
            build_control_branch = build_control_bt_spec.tokenize("/")[-1]
        }
        sh "echo ${build_control_branch} > VAR-build_control_branch"
        sh "echo ${build_control_tag} > VAR-build_control_tag"

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
        "build_control_tag: ${build_control_tag}\n" +
        "  Parameters:\n" +
        "manifest_file: ${this.manifest_file}\n" +
        "labels: ${this.labels}\n" +
        "py_versions: ${this.py_versions}\n" +
        "numpy_versions: ${this.numpy_versions}\n" +
        "conda_installer_version: ${this.conda_installer_version}\n" +
        "conda_version: ${this.conda_version}\n" +
        "conda_build_version: ${this.conda_build_version}\n" +
        "conda_base_URL: ${this.conda_base_URL}\n" +
        "utils_repo: ${this.utils_repo}\n" +
        "old_jobs_action: ${this.old_jobs_action}\n" +
        "  Other values:\n" +
        "dsl_script: ${this.dsl_script}")
    }

    stage("Setup") {
        sh "mkdir -p ${this.ldir}"
        // Obtain libraries to facilitate job generation tasks.
        // Attempt to copy from site cache first, if that fails, try to
        // download from the internet.
        dir ("libs") {
            def cp_status = sh(script: "cp ${site_file_cache_dir}/${yaml_lib_file} .",
               returnStatus: true)
            if (cp_status != 0) {
                sh "curl -O ${yaml_lib_url_base}/${yaml_lib_file}"
            }
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

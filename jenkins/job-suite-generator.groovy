// Top-level pipeline job that provides parameterized machinery for
// creating one or more build job suites for use in building AstroConda
// package sets.
// Uses Job-DSL plugin.

// Directory into which supporting libraries are stored. Gets added to
// groovy classpath definition prior to imports.
this.ldir = "libs"

// DSL script path within the repository obtained for this job.
this.dsl_script = "jenkins/generator_DSL.groovy"


node("master") {

    stage("Prep") {
        // Delete any existing job workspace directory contents.
        deleteDir()

        // These variables are provided by the execution of the generator
        // build task with parameters.  Each var is populated by a parameter
        // specification.
        println("manifest_file: ${this.manifest_file}\n" +
        "label: ${this.label}\n" +
        "py_version: ${this.py_version}\n" +
        "build_control_repo: ${this.build_control_repo}\n" +
        "build_control_branch: ${this.build_control_branch}\n" +
        "conda_version: ${this.conda_version}\n" +
        "conda_build_version: ${this.conda_build_version}\n" +
        "conda_base_URL: ${this.conda_base_URL}\n" +
        "utils_repo: ${this.utils_repo}\n" +
        "old_jobs_action: ${this.old_jobs_action}\n" +
        "dsl_script: ${this.dsl_script}")
    }

    stage("Setup") {
        sh "mkdir -p ${this.ldir}"
        // Obtain libraries to facilitate job generation tasks.
        dir ("libs") {
            sh "curl -O https://repo1.maven.org/maven2/org/yaml/snakeyaml/1.17/snakeyaml-1.17.jar"
        }
        // Copy files from the implicit checkout of the build_control directory (handled by
        // the job that reads this pipeline script) into the actual workspace of this job so
        // the jobDsl call below will be able to find what it needs.
        sh "cp -r ${env.WORKSPACE}@script/* ."
    }

    stage("Spawn job definitions") {
        jobDsl targets: [this.dsl_script].join("\n"),
               lookupStrategy: "SEED_JOB",
               additionalClasspath: ["${this.ldir}/*.jar"].join("\n"),
               removeAction: "${this.old_jobs_action}"
    }

}


// Top-level pipeline job that provides parameterized machinery for
// creating one or more build job suites for use in building AstroConda
// package sets.
// Uses Job-DSL plugin.

// Directory into which supporting libraries are stored. Gets added to
// groovy classpath definition prior to imports.
this.ldir = "libs"

node("master") {

    stage("Prep") {
        // Delete any existing job workspace directory contents.
        deleteDir()

        // These variables are provided by the execution of the generator
        // build task with parameters.  Each var is populated by a parameter
        // specification.
        sh "echo manifest_file=${this.manifest_file}"
        sh "echo label=${this.label}"
        sh "echo py_version=${this.py_version}"
        sh "echo old_jobs_action=${this.old_jobs_action}"
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

    stage('Spawn job definitions') {
        jobDsl targets: ["jenkins/generator_DSL.groovy"].join('\n'),
               lookupStrategy: "SEED_JOB",
               additionalClasspath: ["${this.ldir}/*.jar"].join('\n'),
               removeAction: "${this.old_jobs_action}"
    }

}


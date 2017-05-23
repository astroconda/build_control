node(this.label) {

    dir(this.parent_workspace) {

        env.PATH = "${this.parent_workspace}/miniconda/bin/:" + "${env.PATH}"
        env.PYTHONPATH = ""
        // Make the log files a bit more deterministic
        env.PYTHONUNBUFFERED = "true"
        def time = new Date()

        println("\n" +
        "    Package Build Info Summary:\n" +
        "${time}\n" +
        "JOB_DEF_GENERATION_TIME: ${JOB_DEF_GENERATION_TIME}\n" +
        "inherited workspace: ${this.parent_workspace}\n" +
        "this.Nodelabel: ${this.label}\n" +
        "env.JOB_BASE_NAME: ${env.JOB_BASE_NAME}\n" +
        "env.JOB_NAME: ${env.JOB_NAME}\n" +
        "env.BUILD_NUMBER: ${env.BUILD_NUMBER}\n" +
        "env.NODE_NAME: ${env.NODE_NAME}\n" +
        "env.WORKSPACE: ${env.WORKSPACE}\n" +
        "env.JENKINS_HOME: ${env.JENKINS_HOME}\n" +
        "parameter py_version: ${this.py_version}\n" +
        "PATH: ${env.PATH}\n" +
        "PYTHONPATH: ${env.PYTHONPATH}\n" +
        "PYTHONUNBUFFERED: ${env.PYTHONUNBUFFERED}\n")

        // In the directory common to all package build jobs,
        // run conda build --dirty for this package to use any existing work
        // directory or source trees already obtained.
        dir("conda-recipes") {

            cmd = "conda build"

            stage("Build") {
                build_cmd = cmd
                args = ["--no-test",
                        "--no-anaconda-upload",
                        "--python=${this.py_version}",
                        "--numpy=${this.numpy_version}",
                        "--skip-existing",
                        "--override-channels",
                        "--channel defaults",
                        "--dirty"]
                for (arg in args) {
                    build_cmd = "${build_cmd} ${arg}"
                }
                stat = 999
                stat = sh(script: "${build_cmd} ${env.JOB_BASE_NAME}",
                          returnStatus: true)
                if (stat != 0) {
                    currentBuild.result = "FAILURE"
                }
            }

            stage("Test") {
                build_cmd = cmd
                args = ["--test",
                        "--python=${this.py_version}",
                        "--numpy=${this.numpy_version}"]
                for (arg in args) {
                    build_cmd = "${build_cmd} ${arg}"
                }
                stat = 999
                stat = sh(script: "${build_cmd} ${env.JOB_BASE_NAME}",
                          returnStatus: true)
                if (stat != 0) {
                    currentBuild.result = "UNSTABLE"
                }
            }

        } // end dir
    }

} //end node

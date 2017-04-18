node(this.label) {

    dir(this.parent_workspace) {

        println("inherited workspace: ${this.parent_workspace}")
        println("this.Nodelabel: ${this.label}")
        println("env.JOB_NAME: ${env.JOB_NAME}")
        println("env.JOB_BASE_NAME: ${env.JOB_BASE_NAME}")
        println("env.BUILD_NUMBER: ${env.BUILD_NUMBER}")
        println("env.NODE_NAME: ${env.NODE_NAME}")
        println("env.WORKSPACE: ${env.WORKSPACE}")
        println("env.JENKINS_HOME: ${env.JENKINS_HOME}")
        println(currentBuild.buildVariables)
        println("parameter py_version: ${this.py_version}")

        env.PATH = "${this.parent_workspace}/miniconda/bin/:" + "${env.PATH}"
        println("PATH: ${env.PATH}")

        // Make the log files a bit more deterministic
        env.PYTHONUNBUFFERED = "true"

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
                        "--no-anaconda-upload",
                        "--python=${this.py_version}",
                        "--numpy=${this.numpy_version}",
                        "--skip-existing"]
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

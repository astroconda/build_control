node(this.label) {

    dir(this.parent_workspace) {

        println("inherited workspace: ${this.parent_workspace}")
        println("Nodelabel: ${this.label}")
        println("${env.JOB_NAME}")
        println("${env.JOB_BASE_NAME}")
        println("${env.BUILD_NUMBER}")
        println("${env.NODE_NAME}")
        println("${env.WORKSPACE}")
        println("${env.JENKINS_HOME}")
        println(currentBuild.buildVariables)
        println("parameter py_version: ${this.py_version}")

        env.PATH = "${this.parent_workspace}/miniconda/bin/:" + "${env.PATH}"

        // Make the log files a bit more deterministic
        env.PYTHONUNBUFFERED = "true"

        this.OSname = null
        uname = sh(script: "uname", returnStdout: true).trim()
        if (uname == "Darwin") {
            this.OSname = "MacOSX"
            env.PATH = "${env.PATH}:/sw/bin"
            this.CONDA_BLD_OUTPUT_DIR = "osx-64"
        }
        if (uname == "Linux") {
            this.OSname = uname
            this.CONDA_BLD_OUTPUT_DIR = "linux-64"
        }
        assert uname != null
        println("${this.CONDA_BLD_OUTPUT_DIR}")

        // In directory common to all package build jobs, run conda build for this
        // package.
        dir("conda-recipes") {

            build_cmd = "conda build"

            stage("Build") {
                build_args = "--no-test --no-anaconda-upload --python=${this.py_version}" +
                    " --numpy=${this.numpy_version} --skip-existing"
                stat = 999

                stat = sh(script: "${build_cmd} ${build_args} ${env.JOB_BASE_NAME}",
                            returnStatus: true)
                println("Shell call returned status: ${stat}")
                if (stat != 0) {
                    currentBuild.result = "FAILURE"
                }
            }

            stage("Test") {
                build_args = "--test --no-anaconda-upload --python=${this.py_version}" +
                    " --numpy=${this.numpy_version} --skip-existing"
                stat = sh(script: "${build_cmd} ${build_args} ${env.JOB_BASE_NAME}",
                            returnStatus: true)
                println("Shell call returned status: ${stat}")
                if (stat != 0) {
                    currentBuild.result = "UNSTABLE"
                }
            }

        } // end dir
    }

} //end node

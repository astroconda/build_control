// Parameters inherited environment injection.
//----------------------------------------------------------------------------
// CONDA_BUILD_VERSION  - Conda-build is installed forced to this version.

this.build_status_file = "${this.parent_workspace}/propagated_build_status"

node(this.label) {

    dir(this.parent_workspace) {

        env.PATH = "${this.parent_workspace}/miniconda/bin/:" + "${env.PATH}"
        env.PYTHONPATH = ""
        // Make the log files a bit more deterministic
        env.PYTHONUNBUFFERED = "true"
        def time = new Date()

        sh "env | sort"

        println("\n" +
        "    Package Build Info Summary:\n" +
        "${time}\n" +
        "JOB_DEF_GENERATION_TIME: ${JOB_DEF_GENERATION_TIME}\n" +
        "this.Nodelabel: ${this.label}\n" +
        "env.JOB_BASE_NAME: ${env.JOB_BASE_NAME}\n" +
        "env.JOB_NAME: ${env.JOB_NAME}\n" +
        "env.BUILD_NUMBER: ${env.BUILD_NUMBER}\n" +
        "env.NODE_NAME: ${env.NODE_NAME}\n" +
        "env.WORKSPACE: ${env.WORKSPACE}\n" +
        "env.JENKINS_HOME: ${env.JENKINS_HOME}\n" +
        "parameter build_control_repo: ${this.build_control_repo}\n" +
        "parameter build_control_branch: ${this.build_control_branch}\n" +
        "parameter build_control_tag: ${this.build_control_tag}\n" +
        "parameter parent_workspace: ${this.parent_workspace}\n" +
        "parameter py_version: ${this.py_version}\n" +
        "parameter numpy_version: ${this.numpy_version}\n" +
        "parameter cull_manifest: ${this.cull_manifest}\n" +
        "parameter channel_URL: ${this.channel_URL}\n" +
        "PATH: ${env.PATH}\n" +
        "PYTHONPATH: ${env.PYTHONPATH}\n" +
        "PYTHONUNBUFFERED: ${env.PYTHONUNBUFFERED}\n")

        def build_status = readFile this.build_status_file
        build_status = build_status.trim()

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
                // Use channel URL obtained from manifest in build command if
                // manifest has been culled to allow packages being built to
                // simply download dependency packages from the publication
                // channel as needed, rather than build them as part of the
                // package build session that requires them.
                if (this.cull_manifest == "true") {
                    args.add("--channel ${this.channel_URL}")
                }
                // If conda build 3.x is being used, apply any global package
                // pin values contained in the 'pin_env' conda environment
                // created by the dispatch job by using the --bootstrap flag
                // here.
                if (CONDA_BUILD_VERSION[0] == "3") {
                    args.add("--old-build-string")
                    args.add("--bootstrap pin_env")
                }
                // Compose build command string to use in shell call.
                for (arg in args) {
                    build_cmd = "${build_cmd} ${arg}"
                }
                stat = 999
                stat = sh(script: "${build_cmd} ${env.JOB_BASE_NAME}",
                          returnStatus: true)
                if (stat != 0) {
                    currentBuild.result = "FAILURE"
                    // Ratchet up the overall build status severity if this
                    // is the most severe seen so far.
                    if (build_status != "FAILURE") {
                            sh "echo ${currentBuild.result} > ${this.build_status_file}"
                    }
                }
            }

            // Skip test stage if build stage has failed.
            if (currentBuild.result != "FAILURE") {
                stage("Test") {
                    build_cmd = cmd
                    args = ["--test",
                            "--python=${this.py_version}",
                            "--numpy=${this.numpy_version}",
                            "--override-channels",
                            "--channel defaults"]
                    if (this.cull_manifest == "true") {
                        args.add("--channel ${this.channel_URL}")
                    }
                    if (CONDA_BUILD_VERSION[0] == "3") {
                        args.add("--old-build-string")
                    }
                    for (arg in args) {
                        build_cmd = "${build_cmd} ${arg}"
                    }
                    stat = 999
                    stat = sh(script: "${build_cmd} ${env.JOB_BASE_NAME}",
                              returnStatus: true)
                    if (stat != 0) {
                        currentBuild.result = "UNSTABLE"
                        // Ratchet up the overall build status severity if this
                        // is the most severe seen so far.
                        if (build_status == "SUCCESS") {
                            sh "echo ${currentBuild.result} > ${this.build_status_file}"
                        }
                    }
                }
            }
        } // end dir
    }

} //end node

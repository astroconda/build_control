// Parameters inherited via environment injection at job creation time.
//----------------------------------------------------------------------------
// CONDA_BUILD_VERSION  - Conda-build is installed forced to this version.

node(this.label) {

    // Add any supplemental environment vars to the build environment.
    for (env_var in this.supp_env_vars.trim().tokenize()) {
        def key = env_var.tokenize("=")[0]
        def val = env_var.tokenize("=")[1]
        // env[] assignment requires in-process script approval for signature:
        // org.codehaus.groovy.runtime.DefaultGroovyMethods putAt java.lang.Object
        env[key] = val
    }

    dir(this.parent_workspace) {

        env.PATH = "${this.parent_workspace}/miniconda/bin/:" + "${env.PATH}"
        env.PYTHONPATH = ""
        // Make the output a bit more deterministic
        env.PYTHONUNBUFFERED = "true"
        def time = new Date()

        // Use existing isolated home directory unique to this build provided
        // by _dispatch.
        env.HOME = "${env.WORKSPACE}/home"

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
        "parameter use_version_pins: ${this.use_version_pins}\n" +
        "parameter supp_env_vars: ${this.supp_env_vars}\n" +
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
                        "--dirty"]
                // Use channel URL obtained from manifest in build command if
                // manifest has been culled to allow packages being built to
                // simply download dependency packages from the publication
                // channel as needed, rather than build them as part of the
                // package build session that requires them.
                // Channel arguments are order-dependent.
                if (this.cull_manifest) {
                    args.add("--channel ${this.channel_URL}")
                }
                args.add("--channel defaults")

                // If conda build 3.x is being used, apply any global package
                // pin values contained in the 'pin_env' conda environment
                // created by the dispatch job by using the --bootstrap flag
                // here.
                if (CONDA_BUILD_VERSION[0] == "3") {
                    args.add("--old-build-string")
                    if (this.use_version_pins == "true") {
                        args.add("--bootstrap pin_env")
                    }
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
                }
            }

            // Skip test stage if build stage has failed.
            if (currentBuild.result != "FAILURE") {
                stage("Test") {
                    build_cmd = cmd
                    args = ["--test",
                            "--python=${this.py_version}",
                            "--numpy=${this.numpy_version}",
                            "--override-channels"]
                    // NOTE: Channel arguments are order-sensitive.
                    if (this.cull_manifest) {
                        args.add("--channel ${this.channel_URL}")
                    }
                    args.add("--channel defaults")

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
                        // is the most severe status seen so far.
                        // Also, delete the package file so that it cannot be
                        // published. The package file to remove is the most
                        // recent .tar.bz2 file in the build output directory.
                        bld_dir = "${this.parent_workspace}/miniconda/conda-bld"

                        // Get the most recently created package name.
                        def plat_dir = "${bld_dir}/linux-64"
                        if (!fileExists(plat_dir)) {
                            plat_dir = "${bld_dir}/osx-64"
                        }
                        cmd = "ls -t ${plat_dir}/*.tar.bz2 | head -n1"
                        def pkg_full_name = sh(script: cmd, returnStdout: true)

                        println("Deleting file ${pkg_full_name}")
                        // Use shell call here because file.exists() and file.delete()
                        // simply don't work correctly and report no errors to that effect.
                        stat = sh(script: "rm -f ${pkg_full_name}", returnStatus: true)
                        if (stat != 0) {
                            println("ERROR deleting package file ${pkg_full_name}")
                        }
                    }
                }
            }
        } // end dir
    }

} //end node

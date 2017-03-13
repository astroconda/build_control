// Astroconda build control script for use with the Jenkins CI system
//----------------------------------------------------------------------------
// This script is typically dispatched from a multi-configuration (build
// matrix) job.  It accepts the following parameters controlling its behavior
// at dispatch time:
//
//        label - The build node on which to execute
//                ("nott", "boyle", etc)
// release_type - Determines which manifest to use
//                ("dev", "public", "test", etc)
//   py_version - The version of python to build upon (ships with conda)
//                ("py2.7", "py3.5", ...)
// 
//      Constants controlling the behavior of this script:
//
// Where to obtain this file and the manifest files
this.build_control_URL = "https://github.com/astroconda/build_control"

// The conda version shown in the conda_installers list below is installed
// first, then the version is forced to this value. 
this.conda_version = "4.2.15"

// Conda-build is installed fresh at this version.
this.conda_build_version = "2.1.1"

// Where to get the conda installer
this.conda_base_URL = "https://repo.continuum.io/miniconda/"

// The conda installer script to use for various <OS><py_version> combinations.
this.conda_installers  = ["Linux-py2.7":"Miniconda2-4.2.12-Linux-x86_64.sh",
                          "Linux-py3.5":"Miniconda3-4.2.12-Linux-x86_64.sh",
                          "MacOSX-py2.7":"Miniconda2-4.2.12-MacOSX-x86_64.sh",
                          "MacOSX-py3.5":"Miniconda3-4.2.12-MacOSX-x86_64.sh"]

// The manifest file to use for each release type
this.manifest_files = ["dev":"dev.yaml",
                       "public":"public.yaml",
                       "public_legacy":"public_legacy.yaml",
                       "ETC":"etc.yaml",
                       "dev_rotate_meta_packages":"astroconda.dev_rotate.yaml",
                       "test":"test.yaml"]
//----------------------------------------------------------------------------

node(this.label) {

    this.OSname = null
    def uname = sh(script: "uname", returnStdout: true).trim()
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

    this.BUILD_SUBDIR = "${this.release_type}/${this.py_version}"
    this.BUILD_ROOT = "${env.WORKSPACE}/${this.BUILD_SUBDIR}"
    currentBuild.displayName = 
        "${this.release_type}-${this.OSname}-${this.py_version}-${this.label}"

    // Delete any existing job workspace directory contents.
    // The directory deleted is the one named after the jenkins pipeline job.
    deleteDir()
   
    // Perform the following build job tasks in BUILD_SUBDIR within the
    // topmost workspace directory for this node to allow for multiple
    // parallel jobs using different combinations of build parameters.
    dir("${this.BUILD_SUBDIR}") {

        stage('Setup') {

            println "this.label ${this.label}"
            assert this.label != null
            assert this.label != "label-DEFAULT"
    
            println "this.py_version ${this.py_version}"
            assert this.py_version != null
            assert this.py_version != "py_version-DEFAULT"
    
            println "this.release_type ${this.release_type}"
            assert this.release_type != null
            assert this.release_type != "release_type-DEFAULT"

            // Fetch the manifest files
            git url: this.build_control_URL

            // Check for the availability of a download tool and then use it
            // to get the conda installer.
            def dl_cmds = ["wget --no-verbose --server-response --no-check-certificate",
                           "curl -OSs"]
            def dl_cmd = null
            def stat1 = 999
            for (cmd in dl_cmds) {
                stat1 = sh(script: "which ${cmd.split()[0]}", returnStatus: true)
                if( stat1 == 0 ) {
                    dl_cmd = cmd
                    break
                }
            }
            if (stat1 != 0) {
                println("Could not find a download tool. Unable to proceed.")
                sh "false"
            }

            def conda_installer =
               this.conda_installers["${this.OSname}-${this.py_version}"]
            dl_cmd = dl_cmd + " ${this.conda_base_URL}${conda_installer}"
            sh dl_cmd

            // Make the log files a bit more deterministic
            env.PYTHONUNBUFFERED = "true"

            // Run miniconda installer and then force to particular version
            sh "bash ./${conda_installer} -b -p miniconda"
            env.PATH = "${this.BUILD_ROOT}/miniconda/bin/:" + "${env.PATH}"
            sh "conda install --quiet conda=${this.conda_version}"
            sh "conda install --quiet --yes conda-build=${this.conda_build_version}"

            // TODO: Check for presence of build support tools, such as git,
            // make, compilers, etc. and print a summary of their versions
            // for auditing purposes.

            this.manifest = readYaml file: "manifests/" +
                this.manifest_files["${this.release_type}"]
            println("Manifest repository: ${this.manifest.repository}")
            println("Manifest numpy version specification: " +
                "${this.manifest.numpy_version}")
            println("Manifest packages to build:")
            for (pkgname in this.manifest.packages) {
                println(pkgname)
            }

            println("Checking for supplemental channels specification:")
            if (this.manifest.channels != null) {
                println("Supplemental channel(s) found, adding:")
                for (channel in this.manifest.channels) {
                    sh "conda config --add channels ${channel}"
                }
            } else {
                println("[INFO] No supplemental channels specified in manifest.")
            } 

            // Retrieve conda recipes
            def recipes_dir = "conda-recipes"
            dir(recipes_dir) {
                git url: this.manifest.repository
            }

            // Set git ID info to avoid errors with commands like 'git tag'.
            sh "git config user.email \"ssb-jenkins@ssb-jenkins\""
            sh "git config user.name \"ssb-jenkins\""
        }

        stage('Build packages') {
            def build_cmd = "conda build"
            def pyversion_num = this.py_version.split('y')[1]
            def build_args = "--no-anaconda-upload --python=${pyversion_num}" +
                " --numpy=${this.manifest.numpy_version} --skip-existing"
            def stat2 = 999
            dir ("conda-recipes") {
                for (pkgname in this.manifest.packages) {
                    stat2 = sh(script: "${build_cmd} ${build_args} ${pkgname}",
                                returnStatus: true)
                    println("Shell call returned status: ${stat2}")
                    println "\nEnd Build: ${pkgname} " +
                        "======================================================="
                }
            }

            // Determine if each package in the manifest was built.
            // Set flag to fail the stage in the event of any missing packages.
            def stage_success = "true"
            def outdir = "miniconda/conda-bld/${this.CONDA_BLD_OUTPUT_DIR}"
            dir(outdir) {
                def built_pkgs = sh(script: "ls -1 *.tar.bz2",
                                    returnStdout: true).trim().split()
                def found_pkg = false
                def first = true
                for (pkgname in this.manifest.packages) {
                    found_pkg = false
                    for (built_pkg in built_pkgs) {
                        if ( built_pkg.indexOf(pkgname, 0) != -1) {
                            found_pkg = true
                            break
                        }
                    }
                    if (!found_pkg) {
                        if (first) {
                            println("ERROR BUILDING ONE OR MORE PACKAGES!\n")
                            first = false
                        }
                        println("ERROR building: ${pkgname}")
                        stage_success = "false"
                    }
                }
            }
            sh stage_success
        }

        stage('Tests') {
            println "Test results go here."
        }

        stage('Publish/Archive build products') {
            // Publishing and/or archival steps go here.
        }

    } // end dir()

} //end node


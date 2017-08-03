// Parameters inherited from the calling script via environment injection.
//----------------------------------------------------------------------------
// MANIFEST_FILE        - The "release" type; list of recipes/packages to build
// LABEL                - Node or logical group of build nodes
// PY_VERSION           - Python version hosted by conda to support the build
// BUILD_CONTROL_REPO   - Repository holding this & other build system files,
//                        and manifest files
// BUILD_CONTROL_BRANCH - Branch to obtain from build control repo
// CONDA_VERSION        - First, then the version is forced to this value.
// CONDA_BUILD_VERSION  - Conda-build is installed forced to this version.
// CONDA_BASE_URL       - Where to get the conda installer
// UTILS_REPO           - Repository holding support utilities

// Directories to create within the workspace
this.utils_dir = "utils"
this.recipes_dir = "conda-recipes"

this.build_status_file = "propagated_build_status"

// The conda installer script to use for various <OS><py_version> combinations.
this.conda_installers  = ["Linux-py2.7":"Miniconda2-${CONDA_VERSION}-Linux-x86_64.sh",
                          "Linux-py3.5":"Miniconda3-${CONDA_VERSION}-Linux-x86_64.sh",
                          "MacOSX-py2.7":"Miniconda2-${CONDA_VERSION}-MacOSX-x86_64.sh",
                          "MacOSX-py3.5":"Miniconda3-${CONDA_VERSION}-MacOSX-x86_64.sh"]


node(LABEL) {

    this.OSname = null
    def uname = sh(script: "uname", returnStdout: true).trim()
    if (uname == "Darwin") {
        this.OSname = "MacOSX"
        this.OSdesc = "MacOS"
        this.OSversion = sh(script: "sw_vers -productVersion", returnStdout: true).trim()
        env.PATH = "${env.PATH}:/sw/bin"
        this.CONDA_PLATFORM = "osx-64"
    }
    if (uname == "Linux") {
        this.OSname = uname
        this.OSdesc = sh(script: "lsb_release -i",
                         returnStdout: true).trim().tokenize()[2]
        this.OSversion = sh(script: "lsb_release -r",
                            returnStdout: true).trim().tokenize()[1]
        this.CONDA_PLATFORM = "linux-64"
    }
    assert uname != null

    // Conda paths
    this.conda_install_dir = "${env.WORKSPACE}/miniconda"
    this.conda_build_output_dir = "${this.conda_install_dir}/conda-bld/${this.CONDA_PLATFORM}"

    env.PYTHONPATH = ""
    // Make the log files a bit more deterministic
    env.PYTHONUNBUFFERED = "true"

    sh "env | sort"

    // Delete any existing job workspace directory contents.
    // The directory deleted is the one named after the jenkins pipeline job.
    deleteDir()

    // Get the manifest and build control files
    git branch: BUILD_CONTROL_BRANCH, url: BUILD_CONTROL_REPO

    this.manifest = readYaml file: "manifests/${MANIFEST_FILE}"
    if (this.manifest.channel_URL[-1..-1] == "/") {
        this.manifest.channel_URL = this.manifest.channel_URL[0..-2]
    }

    // Allow for sharing build_list between stages below.
    this.build_list = []

    stage("Summary") {
        def time = new Date()
        def manifest_pkg_txt = ""
        for (pkgname in this.manifest.packages) {
            manifest_pkg_txt = "${manifest_pkg_txt}${pkgname}\n"
        }
        println("\n" +
        "    Build Info Summary:\n" +
        "${time}\n" +
        "JOB_DEF_GENERATION_TIME: ${JOB_DEF_GENERATION_TIME}\n" +
        "OSname: ${this.OSname}\n" +
        "OSDesc: ${this.OSdesc}\n" +
        "OSverion: ${this.OSversion}\n" +
        "script: dispatch.groovy\n" +
        "env.WORKSPACE: ${env.WORKSPACE}\n" +
        "PATH: ${PATH}\n" +
        "PYTHONPATH: ${env.PYTHONPATH}\n" +
        "PYTHONUNBUFFERED: ${env.PYTHONUNBUFFERED}\n" +
        "  Job suite parameters:\n" +
        "LABEL: ${LABEL}\n" +
        "env.NODE_NAME: ${env.NODE_NAME}\n" +
        "PY_VERSION: ${PY_VERSION}\n" +
        "MANIFEST_FILE: ${MANIFEST_FILE}\n" +
        "CONDA_VERSION: ${CONDA_VERSION}\n" +
        "CONDA_BUILD_VERSION: ${CONDA_BUILD_VERSION}\n" +
        "CONDA_BASE_URL: ${CONDA_BASE_URL}\n" +
        "BUILD_CONTROL_REPO: ${BUILD_CONTROL_REPO}\n" +
        "BUILD_CONTROL_BRANCH: ${BUILD_CONTROL_BRANCH}\n" +
        "UTILS_REPO: ${UTILS_REPO}\n" +
        "  Trigger parameters:\n" +
        "this.cull_manifest: ${this.cull_manifest}\n" +
        "  Manifest values:\n" +
        "Recipe repository: ${this.manifest.repository}\n" +
        "Numpy version spec: ${this.manifest.numpy_version}\n" +
        "Channel URL: ${this.manifest.channel_URL}\n" +
        "Package list:\n${manifest_pkg_txt}")
    }

    stage("Setup") {
        // Inherited from env() assignment performed in the generator
        // DSL script.
        assert LABEL != null
        assert LABEL != "label-DEFAULTVALUE"

        // Inherited from env() assignment performed in the generator
        // DSL script.
        assert PY_VERSION != null
        assert PY_VERSION != "py_version-DEFAULTVALUE"
        this.py_maj_version = "${PY_VERSION.tokenize(".")[0]}"

        // Inherited from env() assignment performed in the generator
        // DSL script.
        assert MANIFEST_FILE != null
        assert MANIFEST_FILE != "manifest_file-DEFAULTVALUE"

        // Get conda recipes
        dir(this.recipes_dir) {
            git url: this.manifest.repository
        }

        // Get build utilities
        dir(this.utils_dir) {
            git url: UTILS_REPO
        }

        // Check for the availability of a download tool and then use it
        // to get the conda installer.
        if (CONDA_BASE_URL[-1..-1] == "/") {
            CONDA_BASE_URL = [0..-2]
        }
        def dl_cmds = ["wget --no-verbose --server-response --no-check-certificate",
                       "curl -OSs"]
        def dl_cmd = null
        def stat1 = 999
        for (cmd in dl_cmds) {
            stat1 = sh(script: "which ${cmd.tokenize()[0]}", returnStatus: true)
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
           this.conda_installers["${this.OSname}-py${PY_VERSION}"]
        dl_cmd = dl_cmd + " ${CONDA_BASE_URL}/${conda_installer}"
        sh dl_cmd

        // Install specific versions of miniconda and conda-build
        sh "bash ./${conda_installer} -b -p ${this.conda_install_dir}"
        env.PATH = "${this.conda_install_dir}/bin:${env.PATH}"
        def cpkgs = "conda=${CONDA_VERSION} conda-build=${CONDA_BUILD_VERSION}"
        sh "conda install --quiet --yes ${cpkgs} python=${PY_VERSION}"

        // Apply bugfix patch only to conda_build 2.x
        def conda_build_version = sh(script: "conda-build --version", returnStdout: true)
        def conda_build_maj_ver = conda_build_version.tokenize()[1].tokenize('.')[0]
        if (conda_build_maj_ver == "2") {
            println("conda-build major version ${conda_build_maj_ver} detected. Applying bugfix patch.")
            def filename = "${this.conda_install_dir}/lib/python${PY_VERSION}/" +
                           "site-packages/conda_build/config.py"
            def patches_dir = "${env.WORKSPACE}/patches"
            def patchname = "conda_build_2.1.1_substr_fix_py${this.py_maj_version}.patch"
            def full_patchname = "${patches_dir}/${patchname}"
            sh "patch ${filename} ${full_patchname}"
        }

        // Install support tools
        dir(this.utils_dir) {
            sh "python setup.py install"
        }
    }

    stage("Generate build list") {
        // Generate a filtered, optionally culled, & dependency-ordered list
        // of available package recipes.
        def culled_option = "--culled"
        if (this.cull_manifest == "false") {
            culled_option = ""
        }
        def build_list_file = "build_list"
        cmd = "rambo"
        args = ["--platform ${this.CONDA_PLATFORM}",
                "--python ${PY_VERSION}",
                "--manifest manifests/${MANIFEST_FILE}",
                "--file ${build_list_file}",
                "${culled_option}",
                this.recipes_dir]
        for (arg in args) {
            cmd = "${cmd} ${arg}"
        }
        sh(script: cmd)
        def build_list_text = readFile build_list_file
        this.build_list = build_list_text.trim().tokenize()
        println("Build list:")
        println(build_list_text)

        // Write build status file to facilitate build status propagation
        // from child jobs.
        sh "echo SUCCESS > ${this.build_status_file}"
    }

    stage("Build packages") {
        for (pkg in this.build_list) {
            build job: pkg,
              parameters:
                [string(name: "label", value: env.NODE_NAME),
                 string(name: "build_control_repo", value: BUILD_CONTROL_REPO),
                 string(name: "build_control_branch", value: BUILD_CONTROL_BRANCH),
                 string(name: "py_version", value: PY_VERSION),
                 string(name: "numpy_version",
                        value: "${this.manifest.numpy_version}"),
                 string(name: "parent_workspace", value: env.WORKSPACE),
                 string(name: "cull_manifest", value: this.cull_manifest),
                 string(name: "channel_URL", value: this.manifest.channel_URL)],
              propagate: false
        }
        // Set overall status to that propagated from individual jobs.
        // This will be the most severe status encountered in all sub jobs.
        def tmp_status = readFile this.build_status_file
        tmp_status = tmp_status.trim()
        currentBuild.result = tmp_status
    }

    stage ("Publication") {
       // Copy packages built during this session to the publication path.
       sh(script: "rsync -avzr ${this.conda_build_output_dir}/*.tar.bz2 ${PUBLICATION_PATH}")
       // Use a lock file to prevent two dispatch jobs that finish at the same
       // time from trampling each other's indexing process.
       def lockfile = "${this.conda_build_output_dir}/LOCK-Jenkins"
       def file = new File(lockfile)
       def tries_remaining = 5
       if ( file.exists() ) {
           println("Lockfile already exists, waiting for it to be released...")
           while ( tries_remaining > 0) {
               println("Waiting 3s for lockfile release...")
               sleep(3000)
               if ( !file.exists() ) {
                   break
               }
               tries_remaining-- 
           }
       }
       if ( tries_remaining != 0 ) {
           sh(script: "touch ${lockfile}")
           dir(this.conda_build_output_dir) {
               sh(script: "conda index")
           }
           sh(script: "rm -f ${lockfile}")
       }
    }
}


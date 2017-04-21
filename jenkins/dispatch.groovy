// Parameters inherited from the calling script via environment injection.
//----------------------------------------------------------------------------
// MANIFEST_FILE       - The "release" type; list of recipes/packages to build
// LABEL               - Node or logical group of build nodes
// PY_VERSION          - Python version hosted by conda to support the build
// BUILD_CONTROL_REPO  - Repository holding this & other build system files,
//                       and manifest files
// CONDA_VERSION       - First, then the version is forced to this value.
// CONDA_BUILD_VERSION - Conda-build is installed forced to this version.
// CONDA_BASE_URL      - Where to get the conda installer
// UTILS_REPO          - Repository holding support utilities

// Directories to create within the workspace
this.utils_dir = "utils"
this.recipes_dir = "conda-recipes"

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
        env.PATH = "${env.PATH}:/sw/bin"
        this.CONDA_PLATFORM = "osx-64"
    }
    if (uname == "Linux") {
        this.OSname = uname
        this.CONDA_PLATFORM = "linux-64"
    }
    assert uname != null

    println("NODE_NAME = ${env.NODE_NAME}")

    // Delete any existing job workspace directory contents.
    // The directory deleted is the one named after the jenkins pipeline job.
    deleteDir()

    // Allow for sharing build_list between stages below.
    this.build_list = []

    stage("Setup") {

        // Inherited from env() assignment performed in the generator
        // DSL script.
        println "LABEL = ${LABEL}"
        assert LABEL != null
        assert LABEL != "label-DEFAULTVALUE"

        // Inherited from env() assignment performed in the generator
        // DSL script.
        println("PY_VERSION = ${PY_VERSION}")
        assert PY_VERSION != null
        assert PY_VERSION != "py_version-DEFAULTVALUE"
        this.py_maj_version = "${PY_VERSION.tokenize(".")[0]}"

        // Inherited from env() assignment performed in the generator
        // DSL script.
        println("MANIFEST_FILE = ${MANIFEST_FILE}")
        assert MANIFEST_FILE != null
        assert MANIFEST_FILE != "manifest_file-DEFAULTVALUE"

        if (CONDA_BASE_URL[-1..-1] == "/") {
            CONDA_BASE_URL = [0..-2]
        }

        // Parameters passed at trigger time
        println("this.cull_manifest: ${this.cull_manifest}")

        println("PATH = ${env.PATH}")

        // Fetch the manifest files
        git url: BUILD_CONTROL_REPO

        // Check for the availability of a download tool and then use it
        // to get the conda installer.
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

        // Run miniconda installer and then force to particular version
        sh "bash ./${conda_installer} -b -p miniconda"
        env.PATH = "${env.WORKSPACE}/miniconda/bin/:${env.PATH}"
        sh "conda install --quiet conda=${CONDA_VERSION}"
        sh "conda install --quiet --yes conda-build=${CONDA_BUILD_VERSION}"

        // Apply bugfix patch to conda_build 2.1.1
        def patches_dir = "${env.WORKSPACE}/patches"
        def patch = "${patches_dir}/conda_build_2.1.1_substr_fix_py${this.py_maj_version}.patch"
        dir("miniconda/lib/python${PY_VERSION}/site-packages/conda/conda_build") {
            sh "patch ${patch}"
        }

        this.manifest = readYaml file: "manifests/${MANIFEST_FILE}"
        if (this.manifest.channel_URL[-1..-1] == "/") {
            this.manifest.channel_URL = this.manifest.channel_URL[0..-2]
        }
        println("Manifest repository: ${this.manifest.repository}")
        println("Manifest numpy version specification: " +
            "${this.manifest.numpy_version}")
        println("Manifest channel_URL: ${this.manifest.channel_URL}")
        println("Manifest packages to build:")
        for (pkgname in this.manifest.packages) {
            println(pkgname)
        }

        // Retrieve conda recipes
        dir(this.recipes_dir) {
            git url: this.manifest.repository
        }
    }

    stage("Generate build list") {
        // Obtain build utilities
        dir(this.utils_dir) {
            git url: UTILS_REPO
        }

        // Generate a filtered, optionally culled, & dependency-ordered list
        // of available package recipes.
        def culled_option = "--culled"
        if (this.cull_manifest == "false") {
           culled_option = ""
        }
        def blist_file = "build_list"
        cmd = "${this.utils_dir}/rambo.py"
        args = ["--platform ${this.CONDA_PLATFORM}",
                "--python ${PY_VERSION}",
                "--manifest manifests/${MANIFEST_FILE}",
                "--file ${blist_file}",
                "${culled_option}",
                this.recipes_dir]
        for (arg in args) {
            cmd = "${cmd} ${arg}"
        }
        sh(script: cmd)

        def blist_text = readFile blist_file
        this.build_list = blist_text.trim().tokenize()
        println("Build list:")
        println(this.build_list)
    }

    stage("Build packages") {
        for (pkg in this.build_list) {
            build job: pkg,
              parameters:
                [string(name: "label", value: env.NODE_NAME),
                 string(name: "py_version", value: PY_VERSION),
                 string(name: "numpy_version",
                        value: "${this.manifest.numpy_version}"),
                 string(name: "parent_workspace", value: env.WORKSPACE)],
              propagate: false
        }
    }
}


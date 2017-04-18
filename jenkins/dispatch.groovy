// The conda version shown in the conda_installers list below is installed
// Where to obtain this file and the manifest files
this.build_control_URL = "https://github.com/astroconda/build_control"

// first, then the version is forced to this value.
this.conda_version = "4.2.15"

// Conda-build is installed fresh at this version.
this.conda_build_version = "2.1.1"

// Where to get the conda installer
this.conda_base_URL = "https://repo.continuum.io/miniconda/"

this.recipes_dir = "conda-recipes"

// Support utilities
this.utils_URL = "https://github.com/rendinam/rambo"
this.utils_dir = "utils"

// The conda installer script to use for various <OS><py_version> combinations.
this.conda_installers  = ["Linux-py2.7":"Miniconda2-4.2.12-Linux-x86_64.sh",
                          "Linux-py3.5":"Miniconda3-4.2.12-Linux-x86_64.sh",
                          "MacOSX-py2.7":"Miniconda2-4.2.12-MacOSX-x86_64.sh",
                          "MacOSX-py3.5":"Miniconda3-4.2.12-MacOSX-x86_64.sh"]

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
        this.py_maj_version = "${PY_VERSION.split(".")[0]}"

        // Inherited from env() assignment performed in the generator
        // DSL script.
        println("MANIFEST_FILE = ${MANIFEST_FILE}")
        assert MANIFEST_FILE != null
        assert MANIFEST_FILE != "manifest_file-DEFAULTVALUE"

        println("PATH = ${env.PATH}")

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
           this.conda_installers["${this.OSname}-py${PY_VERSION}"]
        dl_cmd = dl_cmd + " ${this.conda_base_URL}${conda_installer}"
        sh dl_cmd

        // Run miniconda installer and then force to particular version
        sh "bash ./${conda_installer} -b -p miniconda"
        env.PATH = "${env.WORKSPACE}/miniconda/bin/:${env.PATH}"
        sh "conda install --quiet conda=${this.conda_version}"
        sh "conda install --quiet --yes conda-build=${this.conda_build_version}"

        // Apply bugfix patch to conda_build 2.1.1
        def patches_dir = "${env.WORKSPACE}/patches"
        def patch = "${patches_dir}/conda_build_2.1.1_substr_fix_py${this.py_maj_version}.patch"
        dir("miniconda/lib/python${PY_VERSION}/site-packages/conda/conda_build") {
            sh "patch ${patch}"
        }

        this.manifest = readYaml file: "manifests/" +
            this.manifest_file
        if (this.manifest.channel_URL == '/') {
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
            git url: this.utils_URL
        }

        // Generate a filtered, culled, & dependency-ordered list of available
        // package recipes.
        def blist_file = "build_list"
        cmd = "${this.utils_dir}/rambo.py"
        args = ["--platform ${this.CONDA_PLATFORM}",
                "--manifest manifests/${this.manifest_file}",
                "--file ${blist_file}",
                "--culled",
                this.recipes_dir]
        for (arg in args) {
            cmd = "${cmd} ${arg}"
        }
        sh(script: cmd)

        def blist_text = readFile blist_file
        def build_list = blist_text.trim().tokenize()
        println("Build list:")
        println(build_list)
    }

    stage("Build packages") {
        for (pkg in build_list) {
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


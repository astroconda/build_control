// Parameters inherited from the calling script via environment injection.
//----------------------------------------------------------------------------
// MANIFEST_FILE           - The "release" type; list of recipes/packages to build
// LABEL                   - Node or logical group of build nodes
// PY_VERSION              - Python version hosted by conda to support the build
// NUMPY_VERSION           - numpy version used to support the build
// BUILD_CONTROL_REPO      - Repository holding this & other build system files,
//                           and manifest files
// BUILD_CONTROL_BRANCH    - Branch to obtain from build control repo
// BUILD_CONTROL_TAG       - Tag to obtain from build control repo
// CONDA_INSTALLER_VERSION - Conda installer version to use
// CONDA_VERSION           - conda version is forced to this value
// CONDA_BUILD_VERSION     - Conda-build is installed forced to this version.
// CONDA_BASE_URL          - Where to get the conda installer
// UTILS_REPO              - Repository holding support utilities

// Directories to create within the workspace
this.utils_dir = "utils"
this.recipes_dir = "conda-recipes"

this.build_status_file = "propagated_build_status"

// The conda installer script to use for various <OS><py_version> combinations.
this.conda_installers  = ["Linux-py2":"Miniconda2-${CONDA_INSTALLER_VERSION}-Linux-x86_64.sh",
                          "Linux-py3":"Miniconda3-${CONDA_INSTALLER_VERSION}-Linux-x86_64.sh",
                          "MacOSX-py2":"Miniconda2-${CONDA_INSTALLER_VERSION}-MacOSX-x86_64.sh",
                          "MacOSX-py3":"Miniconda3-${CONDA_INSTALLER_VERSION}-MacOSX-x86_64.sh"]

// Values controlling the conda index stage which happens after any packages are created.
this.max_publication_tries = 5
this.publication_lock_wait_s = 10

// Name of YAML file that contains global pinning information to use during the build.
// Packages that appear in this file will be pinned to the version indicated.
this.version_pins_file = "version_pins.yml"


node(LABEL) {

    // Add any supplemental environment vars to the build environment.
    for (env_var in this.supp_env_vars.trim().tokenize()) {
        def key = env_var.tokenize("=")[0]
        def val = env_var.tokenize("=")[1]
        // env[] assignment requires in-process script approval for signature:
        // org.codehaus.groovy.runtime.DefaultGroovyMethods putAt java.lang.Object
        env[key] = val
    }

    // Delete any existing job workspace directory contents.
    // The directory deleted is the one named after the jenkins pipeline job.
    deleteDir()

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

    // Provide an isolated home directory unique to this build.
    sh "mkdir home"
    env.HOME = "${env.WORKSPACE}/home"

    sh "env | sort"

    // Get the build control files
    git branch: BUILD_CONTROL_BRANCH, url: BUILD_CONTROL_REPO

    // Get manifests by cloning the submodule
    sh(script: "git submodule init")
    sh(script: "git submodule update")

    // If a tag was specified in the job-suite-generator configuration,
    // explicitly check out that tag after cloning the (master) branch,
    // since the 'git' pipeline step does not yet support accessing tags.
    if (BUILD_CONTROL_TAG != "") {
        sh(script: "git checkout tags/${BUILD_CONTROL_TAG}")
    }

    this.manifest = readYaml file: "manifests/${MANIFEST_FILE}"
    if (this.manifest.channel_URL[-1..-1] == "/") {
        this.manifest.channel_URL = this.manifest.channel_URL[0..-2]
    }

    this.pins_file = readYaml file: "jenkins/${this.version_pins_file}"

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
        "NUMPY_VERSION: ${NUMPY_VERSION}\n" +
        "MANIFEST_FILE: ${MANIFEST_FILE}\n" +
        "CONDA_INSTALLER_VERSION: ${CONDA_INSTALLER_VERSION}\n" +
        "CONDA_VERSION: ${CONDA_VERSION}\n" +
        "CONDA_BUILD_VERSION: ${CONDA_BUILD_VERSION}\n" +
        "CONDA_BASE_URL: ${CONDA_BASE_URL}\n" +
        "BUILD_CONTROL_REPO: ${BUILD_CONTROL_REPO}\n" +
        "BUILD_CONTROL_BRANCH: ${BUILD_CONTROL_BRANCH}\n" +
        "BUILD_CONTROL_TAG: ${BUILD_CONTROL_TAG}\n" +
        "UTILS_REPO: ${UTILS_REPO}\n" +
        "  Trigger parameters:\n" +
        "this.cull_manifest: ${this.cull_manifest}\n" +
        "  Manifest values:\n" +
        "Recipe repository: ${this.manifest.repository}\n" +
        "Numpy version spec: ${this.manifest.numpy_version}\n" +
        "Channel URL: ${this.manifest.channel_URL}\n" +
        "Publication root: ${this.manifest.publication_root}\n" +
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
            git branch: this.manifest.git_ref_spec, url: this.manifest.repository
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
           this.conda_installers["${this.OSname}-py${this.py_maj_version}"]
        dl_cmd = dl_cmd + " ${CONDA_BASE_URL}/${conda_installer}"
        sh dl_cmd

        // Install specific versions of miniconda and conda-build
        sh "bash ./${conda_installer} -b -p ${this.conda_install_dir}"
        env.PATH = "${this.conda_install_dir}/bin:${env.PATH}"
        def cpkgs = "conda=${CONDA_VERSION} conda-build=${CONDA_BUILD_VERSION}"
        sh "conda install --quiet --yes ${cpkgs} python=${PY_VERSION}"

        // Apply bugfix patch only to conda_build 2.x
        // py2 conda-build outputs version string to stderr
        // whereas the py3 version outputs it to stdout. Merge output streams here to capture
        // all output under both circumstances.
        def conda_build_version = sh(script: "conda-build --version 2>&1", returnStdout: true).trim()
        def conda_build_maj_ver = conda_build_version.tokenize()[1].tokenize('.')[0]
        def conda_build_min_ver = conda_build_version.tokenize()[1].tokenize('.')[1]
        if (conda_build_maj_ver == "2") {
            println("conda-build major version ${conda_build_maj_ver} detected. Applying bugfix patch.")
            def filename = "${this.conda_install_dir}/lib/python${PY_VERSION}/" +
                           "site-packages/conda_build/config.py"
            def patches_dir = "${env.WORKSPACE}/patches"
            def patchname = "conda_build_2.1.1_substr_fix_py${this.py_maj_version}.patch"
            def full_patchname = "${patches_dir}/${patchname}"
            sh "patch ${filename} ${full_patchname}"
        }
        if (conda_build_maj_ver == "3" && conda_build_min_ver == "0") {
            println("conda-build major version ${conda_build_maj_ver} detected. Applying bugfix patch.")
            def filename = "${this.conda_install_dir}/lib/python${PY_VERSION}/" +
                           "site-packages/conda_build/config.py"
            def patches_dir = "${env.WORKSPACE}/patches"
            def patchname = "conda_build_3.0.15_substr_fix2.patch"
            def full_patchname = "${patches_dir}/${patchname}"
            sh "patch ${filename} ${full_patchname}"
        }

        // Determine if version_pins_file has a list of packages to pin.
        try {
            this.pins_file.packages
            this.use_version_pins = "true"
        } catch(MissingPropertyException) {
            println("${this.version_pins_file} has no packages list, skipping pin environment creation.")
            this.use_version_pins = "false"
        }

        // (conda-build 3.x only)
        // Create and populate environment to be used for pinning reference when
        // building packages via the --bootstrap flag. Environment creation is done
        // using the explicit packages and versions in the pin file, with no
        // dependencies.
        if (CONDA_BUILD_VERSION[0] == "3" && this.use_version_pins == "true") {
            println("Creating environment based on package pin values found \n" +
            "in ${this.version_pins_file} to use as global version pinnning \n" +
            "specification. Packages to be installed in pin environment:")
            println(this.pins_file.packages)
            def env_cmd = "conda create --quiet --no-deps -n pin_env python=${PY_VERSION}"
            for (pkg in this.pins_file.packages) {
                env_cmd = "${env_cmd} ${pkg.tokenize()[0]}=${pkg.tokenize()[1]}"
            }
            sh "${env_cmd}"
            sh "source activate pin_env; conda env list; conda list"
        }

        // Install support tools
        dir(this.utils_dir) {
            sh "python setup.py install"
        }
    }

    stage("Generate build list") {
        // Generate a filtered, optionally culled, & dependency-ordered list
        // of available package recipes.
        def culled_option = ""
        if (this.cull_manifest == "true") {
            culled_option = "--culled"
        }
        def build_list_file = "build_list"
        cmd = "rambo"
        args = ["--platform ${this.CONDA_PLATFORM}",
                "--python ${PY_VERSION}",
                "--numpy ${NUMPY_VERSION}",
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
              parameters: [
                 string(name: "label", value: env.NODE_NAME),
                 string(name: "build_control_repo", value: BUILD_CONTROL_REPO),
                 string(name: "build_control_branch", value: BUILD_CONTROL_BRANCH),
                 string(name: "build_control_tag", value: BUILD_CONTROL_TAG),
                 string(name: "py_version", value: PY_VERSION),
                 string(name: "numpy_version", value: NUMPY_VERSION),
                 string(name: "parent_workspace", value: env.WORKSPACE),
                 string(name: "manifest_file", value: MANIFEST_FILE),
                 string(name: "cull_manifest", value: this.cull_manifest),
                 string(name: "channel_URL", value: this.manifest.channel_URL),
                 string(name: "use_version_pins", value: this.use_version_pins),
                 text(name: "supp_env_vars", value: this.supp_env_vars)
              ],
              propagate: false
        }
        // Set overall status to that propagated from individual jobs.
        // This will be the most severe status encountered in all sub jobs.
        def tmp_status = readFile this.build_status_file
        tmp_status = tmp_status.trim()
        currentBuild.result = tmp_status
    }

    stage ("Publish") {
        def publication_path = "${this.manifest.publication_root}/${this.CONDA_PLATFORM}"
        // Copy and index packages if any were produced in the build.
        def artifacts_present =
            sh(script: "ls ${this.conda_build_output_dir}/*.tar.bz2 >/dev/null 2>&1",
               returnStatus: true)
        def rsync_cmd = "rsync -avzr --ignore-existing"
        if (artifacts_present == 0) {
            sh(script: "${rsync_cmd} ${this.conda_build_output_dir}/*.tar.bz2 ${publication_path}")
            // Use a lock file to prevent two dispatch jobs that finish at the same
            // time from trampling each other's indexing process.
            def lockfile = "${publication_path}/LOCK-Jenkins"
            def file = new File(lockfile)
            def tries_remaining = this.max_publication_tries
            if (file.exists()) {
                println("Lockfile already exists, waiting for it to be released...")
                while ( tries_remaining > 0) {
                    println("Waiting ${this.publication_lock_wait_s}s for lockfile release...")
                    sleep(this.publication_lock_wait_s)
                    if ( !file.exists() ) {
                        break
                    }
                    tries_remaining--
                }
            }
            if (tries_remaining != 0) {
                sh(script: "touch ${lockfile}")
                dir(this.conda_build_output_dir) {
                    sh(script: "conda index ${publication_path}")
                }
                sh(script: "rm -f ${lockfile}")
            }
        } else {
            println("No build artifacts found.")
        }
    }

    // If spec file generation was requested at trigger time, generate one.
    // Create a conda environment containing all packages specified in the manifest.
    specfile_type = ""
    try {
        specfile_type = this.manifest.specfile_type
    } catch(all) {
        println("specfile_type not found in manifest. Not creating spec file.")
    }

    specfile_output = ""
    try {
        specfile_output = this.manifest.specfile_output
    } catch(all) {
        println("specfile_output not found in manifest. Not creating spec file.")
        // prepare to skip specfile_creation
        specfile_type = "NONE"
    }

    if (specfile_type == "jwstdp") {
        stage("Create spec file") {
            // If an 'ASCO_GIT_REV_jwst' has been defined, use it to crate the spec file name
            // otherwise, just use the latest package and leave out the version from the spec
            // file name.
            package_name = "jwst"
            jwst_git_rev = sh(script: "echo \$ASCO_GIT_REV_jwst", returnStdout: true).trim()
            if (jwst_git_rev != "") {
                package_name = "${package_name}=${jwst_git_rev}.dev0"
            } else {
                jwst_git_rev = new Date().format("yyyyMMdd:HHmm")
            }
            cmd = "conda create -n spec -q -y -c ${this.manifest.channel_URL} -c defaults python=${PY_VERSION} ${package_name}"
            sh(script: cmd)

            short_plat = CONDA_PLATFORM.tokenize("-")[0]
            //short_py_ver = PY_VERSION.replaceAll(".", "")
            short_py_ver = "${PY_VERSION[0]}${PY_VERSION[2]}"
            specfile_name = "${specfile_type}-${jwst_git_rev}-${short_plat}-py${short_py_ver}.00.txt"
            //outdir = "/eng/ssb/websites/ssbpublic/astroconda-releases-staging"
            outdir = specfile_output
            outfile = "${outdir}/${specfile_name}"
            sh(script: "conda list -n spec --explicit > ${outfile}")
        }
    }

    stage ("Cleanup") {
        // Clean up the workspace to conserve disk space. Conda installations
        // especially consume a fair amount.
        // The directory deleted is the one named after the jenkins pipeline job.
        deleteDir()
    }
}


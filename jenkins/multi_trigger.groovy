// Trigger one or more jobs, collect results from each triggered job and e-mail
// a status summary to the recipients defined in the 'email_recipients' job
// parameter.

node('master') {

    tasks = [:]
    build_objs = [:]
    build_types = []
    
    stage("Trigger") {
        platforms = platforms.tokenize().sort().reverse()
        def os_list = []
        // Compose list of unique OS values.
        for (platform in platforms) {
            os = platform.tokenize("_")[1]
            if (!os_list.contains(os)) {
                os_list.add(os)
            }
        }

        // Determine if the master platform has been overridden
        // by the specification of a valid platform name substring.
        def override_master_platform = false
        for (platform in platforms) {
            if (platform.contains(non_python_pkg_platform)) {
                override_master_platform = true
                break
            }
        }

        if (override_master_platform) {
            for (platform in platforms) {
                if (platform.contains(non_python_pkg_platform)) {
                    master_platforms.add(platform)
                }
            }
            println("Automatic master_platforms overridden by job parameter." +
                    " Building non-python packages only on master_platforms: ${master_platforms}")
        } else {
            // Compose automatic list of master platforms.
            def master_platforms = []
            for (osval in os_list) {
                for (platform in platforms) {
                    if (platform.contains(osval)) {
                        master_platforms.add(platform)
                        break
                    }
                }
            }
            println("Building non-python packages only on master_platforms: ${master_platforms}")
        }


        for (platform in platforms) {
            build_type = platform.tokenize("_")[0]
            def platname = platform  // vars referenced within 'tasks' block
                                     // below must be defined within for loop.
            build_types += build_type
            // Select one platform from each OS to host all package
            // builds that do not have any python dependencies.
            // The non-python package builds are identical between the
            // various python-version-variation job suites, so only
            // one platform's build is actually necessary on a given OS.
            // The platform selected for this purpose is called the
            // 'master' platform and is defined here to be the platform
            // name that appears first in a reverse lexicographical
            // sorting of the platform names for a given OS.
            def filter_nonpython = true
            if (master_platforms.contains(platname)) {
                filter_nonpython = false
            }

            tasks["${platname}"] = {
                build_objs["${platname}"] = build(
                    job: "/AstroConda/${platname}/_dispatch",
                    parameters: [
                        booleanParam(name: 'cull_manifest',
                                     value: cull_manifest.toBoolean()
                        ),
                        booleanParam(name: 'filter_nonpython',
                                     value: filter_nonpython
                        ),
                        textParam(name: 'supp_env_vars',
                                  value: supp_env_vars
                        )
                    ],
                    propagate: false)
            } // end tasks
            is_master = false
        } // end for
    }
    
    stage("Report") {
        // Parallel execution of the code blocks defined within the 'tasks' map.
        parallel(tasks)

        println("Results...")
        results_msg = ""

        // Determine if all build types are the same, or if this was a mixed-type build.
        compare = build_types[0]
        build_type = compare
        if (build_types.size() > 1) {
            for (type in build_types[1..-1]) {
                if (type != compare) {
                    build_type = "mixed"
                    break
                }
            }
        }

        // Compose status summary. Send mail if recipients have been specified.
        platcount = build_objs.size()
        successes = 0
        build_objs.each {
            key, value ->
                if (value.result == "SUCCESS") {
                    successes++
                }
                // Check for early abort of _dispatch job before description exists.
                if (value.description == null) {
                    result = "ERROR"
                } else {
                    result = value.result
                }
                results_msg = "${results_msg}${key} build #: ${value.number}, result: ${result}\n"
                if (value.description != null) {
                    for (pkg_result in value.description.split('\n')) {
                        results_msg = "${results_msg}${pkg_result}\n"
                    }
                }
                results_msg = "${results_msg}\n"
        }
        println(results_msg)
        def recipients = mail_recipients.replaceAll("\n", " ").trim()
        if (recipients != "") {
            def subject = "Build summary, ${build_type} - ${successes}/${platcount} platforms successful"
            mail body: results_msg, subject: subject, to: recipients, from: "jenkins@boyle.stsci.edu"
        } else {
            println("e-mail not sent: No recipients specified.")
        }
    }
}

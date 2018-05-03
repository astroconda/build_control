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
        // Compose list of master platforms
        def master_platforms = []
        for (osval in os_list) {
            for (platform in platforms) {
                if (platform.contains(osval)) {
                    master_platforms.add(platform)
                    break
                }
            }
        }
        println("master_platforms: ${master_platforms}")

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

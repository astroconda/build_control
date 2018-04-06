// Trigger one or more jobs, collect results from each triggered job and e-mail
// a status summary to the recipients defined in the 'email_recipients' job
// parameter.

node('master') {

    tasks = [:]
    build_objs = [:]
    build_types = []
    
    stage("Trigger") {
        for (platform in platforms.tokenize()) {
            build_type = platform.tokenize("_")[0]
            build_types += build_type
            def platname = platform  // must be inside for loop
            println("platname = ${platname}")
            tasks["${platname}"] = {
                build_objs["${platname}"] = build(
                    job: "/AstroConda/${platname}/_dispatch",
                    parameters: [
                        booleanParam(name: 'cull_manifest',
                                     value: cull_manifest.toBoolean()
                        )
                    ],
                    propagate: false)
            } // end tasks
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
            key, value -> results_msg = "${results_msg}${key} build #: ${value.number}, result: ${value.result}\n"
            if (value.result == "SUCCESS") {
                successes++
            }
            for (pkg_result in value.description.split('\n')) {
                results_msg = "${results_msg}${pkg_result}\n"
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

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
        println(build_types)
        results_msg = ""

        compare = build_types[0]
        build_type = compare
        for (type in build_types[1..-1]) {
            if (type != compare) {
                build_type = "mixed"
                break
            }
        }

        platcount = build_objs.size()
        successes = 0
        build_objs.each {
            key, value -> results_msg = "${results_msg}${key} build #: ${value.number}, result: ${value.result}\n"
            for (pkg_result in value.description.split('\n')) {
                if (pkg_result == "SUCCESS") {
                    successes++
                }
                results_msg = "${results_msg}${pkg_result}\n"
            }
            results_msg = "${results_msg}\n"
        }
        println(results_msg)
        def recipients = mail_recipients.replaceAll("\n", " ")
        def subject = "Build summary, ${build_type} - ${successes}/${platcount} platforms successful"
        mail body: results_msg, subject: subject, to: recipients, from: "jenkins@boyle.stsci.edu"
    }

}

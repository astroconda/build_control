// Trigger one or more jobs, collect results from each triggered job and e-mail
// a status summary to the recipients defined in the 'email_recipients' job
// parameter.

node('master') {

    tasks = [:]
    build_objs = [:]
    
    stage("Trigger") {
        for (platform in platforms.tokenize()) {
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
        parallel(tasks)
        println("Results...")
        results_msg = ""
        build_objs.each{
            key, value -> results_msg = "${results_msg}${key} build #: ${value.number}, result: ${value.result}\n"
            for (pkg_result in value.description.split('\n')) {
                results_msg = "${results_msg}${pkg_result}\n"
            }
        }
        println(results_msg)
        def recipients = mail_recipients.replaceAll("\n", " ")
        mail body: results_msg, subject: "Build summary", to: recipients, from: "jenkins@boyle.stsci.edu"
    }

}

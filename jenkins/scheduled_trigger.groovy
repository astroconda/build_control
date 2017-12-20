// Triggers the execution of one or more jobs found within 'abs_jobs_folder'.
//
// This job may be scheduled to run at particular intervals to schedule
// collections of jobs simultaneously.
//
// Parameters to be passed to the triggered jobs are defined in the job
// configuration interface within Jenkins.
//   PLATFORMS
//   abs_jobs_folder
//
// Parameter names that are prefixed by the text appearing as the value of
// 'this.job_param_id' will be passed along as run parameters to each job
// that this job triggers.

this.job_param_id = "(downstream)"

node("master") {

    // Print version info
    build_control_repo = scm.getUserRemoteConfigs()[0].getUrl()
    build_control_bt_spec = scm.branches[0].toString()
    if (build_control_bt_spec.find("tags") != null) {
        build_control_branch = "master"
        build_control_tag = build_control_bt_spec.tokenize("/")[-1]
    } else { // a branch, including */master
        build_control_branch = build_control_bt_spec.tokenize("/")[-1]
    }
    println("Build control repo: ${build_control_repo}")
    println("Build control branch: ${build_control_branch}")

    // From Credentials Binding plugin:
    withCredentials([usernamePassword(credentialsId: 'ScopedJenkinsLocal',
                     usernameVariable: 'USERNAME',
                     passwordVariable: 'PASSWORD')]) {

        println(params)
        // Collect the parameters to pass along to triggered jobs (as opposed
        // to parameters used to control the behavior of this job). Compose
        // the URL string used to pass those parameter values to the jobs
        // being triggered via the REST API.
        params_url = ""
        params.each {
             key = it.key.toString()
             val = it.value.toString()
             if (key.find(this.job_param_id) != null) {
                 println("${key}, ${val}")
                 index = this.job_param_id.size()
                 param_name = key[index..-1].trim()
                 println(param_name)
                 params_url = "${params_url}${param_name}=${val}\\&"
                 println("LOOP params_url: ${params_url}")
             }
        }

        // Obtain authentication "crumb" for this session.
        url_base = env.JENKINS_URL.split("://")[1]
        crumb_url_path = "crumbIssuer/api/xml?xpath=concat(//crumbRequestField,\":\",//crumb)"
        crumb_url = "http://${USERNAME}:${PASSWORD}@${url_base}/${crumb_url_path}"
        CRUMB = sh (script: "curl -s '${crumb_url}'", returnStdout: true).trim()
        println("CRUMB: ${CRUMB}")

        // Trigger all requested jobs with supplied parameter(s).
        println("Platforms:\n${PLATFORMS}")
        for (platform in PLATFORMS.tokenize()) {
            println("Triggering _dispatch job for ${platform}...")
            //trigger_url = "http://${url_base}/job/${abs_jobs_folder}/job/${platform}/" +
            //   "job/_dispatch/buildWithParameters${params_url} " +
            //   "-u ${USERNAME}:${PASSWORD}"
            trigger_url = "http://${url_base}/job/${abs_jobs_folder}/job/${platform}/" +
               "job/_dispatch/buildWithParameters?${params_url} " +
               "-u ${USERNAME}:${PASSWORD}"
            println(trigger_url)
            sh (script: "curl -s -S -X POST -H ${CRUMB} ${trigger_url}")
        }
    }
}

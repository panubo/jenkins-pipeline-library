def call(Map params = [:]) {
    def projectName = (params.containsKey('projectName') ? params.projectName : env.JOB_NAME).toLowerCase().replace(" ","").split("/")[0]
    def branch = params.containsKey('branch') ? params.branch : env.BRANCH_NAME
    def defaultBranch = params.containsKey('defaultBranch') ? params.defaultBranch : "master"
    def cacheBucket = params.containsKey('cacheBucket') ? params.cacheBucket : env.DOCKER_CACHE_BUCKET
    def cacheBucketRegion = params.containsKey('cacheBucketRegion') ? params.cacheBucketRegion : env.DOCKER_CACHE_REGION
    def cacheBucketCredentials = params.containsKey('cacheBucketCredentials') ? params.cacheBucketCredentials : env.DOCKER_CACHE_CREDENTIALS
    def composeFile = params.containsKey('composeFile') ? params.composeFile : "dc-ci.yml"

    if (branch == null) {
        error 'dockerPrimeCache must be used as part of a Multibranch Pipeline *or* a `branch` argument must be provided'
    }

    // TODO: Scan for docker-compose files, [composeFile, "docker-compose.yml"]
    if (fileExists("${composeFile}")) {
        def cacheImages = []
        def cacheIds    = []
        // Read docker-compose.yml file
        def composeContent = readYaml file: composeFile
        // Loop over services in docker-compose.yml
        composeContent.services.each { k, v -> 
            // Only cache images we're actually building. ie has build instead of image in service
            if (v.build) {
                // Set default context and dockerfile is they aren't set
                def context   = '.'
                def dockerfile = 'Dockerfile'
                if (v.build instanceof Map && v.build.context) {
                    context = v.build.context
                }
                if (v.build instanceof Map && v.build.dockerfile) {
                    dockerfile = v.build.dockerfile
                }
                def dockerContents = readFile("${context}/${dockerfile}").split("\\r?\\n")
                dockerContents.each {
                    def result = (it =~ /^LABEL.*ci.cache-id[ =]([a-zA-Z-_]+).*/)
                    if (result.matches()) {
                        cacheImages.add(result[0][1])
                    }
                }
            }
        }

        cacheImages.each {
            def cacheId = sh(script: "docker images --filter \"label=ci.cache-id=${it}\" -q | head -n1", returnStdout: true).trim()
            def cacheHistory = sh(script: "docker history ${cacheId} -q | grep -v missing", returnStdout: true).trim().split("\\r?\\n").toList()
            // echo(cacheHistory.dump())

            //.split("\\r?\\n")
            //echo(cacheHistory.dump())
            cacheIds.add(cacheId)
            cacheIds.add(cacheHistory)
        }

        // echo(cacheIds.flatten().dump())
        def saveIds = cacheIds.flatten().join(" ")
        sh("docker save ${saveIds} | lz4 > save.tar.lz4")
        withAWS(region:cacheBucketRegion, credentials:cacheBucketCredentials) {
            s3Upload(file:'save.tar.lz4', bucket:cacheBucket, path:"${projectName}_${branch}.tar.lz4")
        }
    }
}

def call(Map params = [:]) {
    def projectName = (params.containsKey('projectName') ? params.projectName : env.JOB_NAME).toLowerCase().replace(" ","").split("/")[0]
    def branch = (params.containsKey('branch') ? params.branch : env.BRANCH_NAME).replace(" ","").replace("/","_")
    def defaultBranch = params.containsKey('defaultBranch') ? params.defaultBranch : "master"
    def cacheBucket = params.containsKey('cacheBucket') ? params.cacheBucket : env.DOCKER_CACHE_BUCKET
    def cacheBucketRegion = params.containsKey('cacheBucketRegion') ? params.cacheBucketRegion : env.DOCKER_CACHE_REGION
    def cacheBucketCredentials = params.containsKey('cacheBucketCredentials') ? params.cacheBucketCredentials : env.DOCKER_CACHE_CREDENTIALS

    if (branch == null) {
        error 'dockerPrimeCache must be used as part of a Multibranch Pipeline *or* a `branch` argument must be provided'
    }

    echo("Docker PRIME - ${projectName} ${branch}")

    withAWS(region:cacheBucketRegion, credentials:cacheBucketCredentials) {
        def files = s3FindFiles(bucket:cacheBucket, glob:"${projectName}_*.tar.lz4")
        // echo(files.dump())
        def fileNames = files.collect { it.name }
        // echo(fileNames.dump())
        if (fileNames.contains("${projectName}_${branch}.tar.lz4".toString())) {
            echo("Found cache for ${branch}")
            s3Download(file:'load.tar.lz4', bucket:cacheBucket, path:"${projectName}_${branch}.tar.lz4")
        } else if (fileNames.contains("${projectName}_${defaultBranch}.tar.lz4".toString()))  {
            echo("Found cache for ${defaultBranch}")
            s3Download(file:'load.tar.lz4', bucket:cacheBucket, path:"${projectName}_${defaultBranch}.tar.lz4")
        } else {
            echo("No cache found")
        }
        if (fileExists("load.tar.lz4")) {
            sh('lz4 -d < load.tar.lz4 | docker load')
            sh('rm -f load.tar.lz4')
        }
    }
}

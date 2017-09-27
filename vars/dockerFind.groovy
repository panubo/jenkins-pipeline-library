def call() {
    def dockerBuilds = []
    // def utils = new com.panubo.Utils()
    if (fileExists('Dockerfile')) {
        dockerBuilds = ['.']
    } else {
        dockerBuilds = sh(returnStdout: true, script: 'find . -maxdepth 2 -name Dockerfile -exec dirname {} \\;')
            .trim()
            .split(System.getProperty("line.separator"))
    }
    return dockerBuilds
}

def call() {
    def dockerBuilds = []
    def utils = new com.panubo.Utils()
    if (fileExists('Dockerfile')) {
        dockerBuilds = ['.']
    } else {
        dockerBuilds = utils.readLines(sh(returnStdout: true, script: 'find . -maxdepth 2 -name Dockerfile -exec dirname {} \\;'))
    }
    return dockerBuilds
}

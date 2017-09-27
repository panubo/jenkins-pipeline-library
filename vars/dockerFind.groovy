def call() {
    def dockerBuilds = []
    if (fileExists('Dockerfile')) {
        dockerBuilds = ['.']
    } else {
        dockerBuilds = sh(returnStdout: true, script: 'find . -maxdepth 2 -name Dockerfile -exec dirname {} \\;').readLines()
    }
    return dockerBuilds
}

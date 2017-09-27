def call() {
    if (fileExists('Dockerfile')) {
        def dockerBuilds = ['.']
    } else {
        def dockerBuilds = sh(returnStdout: true, script: 'find . -maxdepth 2 -name Dockerfile -exec dirname {} \\;').readLines()
    }
    return dockerBuilds
}

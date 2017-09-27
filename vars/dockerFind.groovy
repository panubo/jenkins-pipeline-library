def call() {
    def dockerBuilds = []
    // def utils = new com.panubo.Utils()
    if (fileExists('Dockerfile')) {
        dockerBuilds = ['.']
    } else {
        echo sh(returnStdout: true, script: 'find . -maxdepth 2 -name Dockerfile -exec dirname {} \\;')
            .trim()
            .split(System.getProperty("line.separator"))

        dockerBuilds = []
    }
    return dockerBuilds
}

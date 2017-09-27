def call() {
    def results = []
    // def utils = new com.panubo.Utils()
    if (fileExists('Dockerfile')) {
        results = ['.']
    } else {
        rawResults = sh(returnStdout: true, script: 'find . -maxdepth 2 -name Dockerfile -exec dirname {} \\;')
            .trim()
            .split(System.getProperty("line.separator"))
        results = rawResults.collect{
            it.replaceFirst(/\.\//, '')
        }
    }
    return results
}

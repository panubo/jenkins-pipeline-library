def call(Map params = [:]) {
    def branch = (params.containsKey('branch') ? params.branch : env.BRANCH_NAME).replace(" ","").replace("/","_")
    def buildNumber = params.containsKey('buildNumber') ? params.buildNumber : env.BUILD_NUMBER
    return "${branch}-${buildNumber}".toString()
}

def call(Closure body) {
    docker.image('panubo/docker:latest').withRun("--privileged --cap-add=ALL -v /lib/modules:/lib/modules:ro") { c ->
        echo "${c.id}"
        docker.image('panubo/docker:latest').inside("--link ${c.id.take(12)}:docker -e DOCKER_HOST=tcp://docker:2375") {
            body()
        }
    }
}

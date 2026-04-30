"""Macros for building LocalStack test container images."""

load("@rules_oci//oci:defs.bzl", "oci_image", "oci_load")
load("@rules_pkg//:pkg.bzl", "pkg_tar")

def localstack_image(name, handler_jars, init_resources, repo_tag):
    """Creates a LocalStack OCI image with handler JARs and init resources baked in.

    Generates the following targets:
      - {name}-handlers-tar: pkg_tar containing handler deploy JARs
      - {name}-init-resources-tar: pkg_tar containing the init resources script
      - {name}-image: oci_image layered on top of the LocalStack base
      - {name}-load: oci_load to make the image available to Testcontainers

    Args:
        name: Base name for the generated targets.
        handler_jars: List of handler deploy JAR labels to include.
        init_resources: Label for the init_resources.py script.
        repo_tag: Docker repo tag for the loaded image (e.g. "bazel/my-service-local:latest").
    """
    pkg_tar(
        name = name + "-handlers-tar",
        testonly = True,
        srcs = handler_jars,
        package_dir = "/opt/code/localstack/",
    )

    pkg_tar(
        name = name + "-init-resources-tar",
        testonly = True,
        srcs = [init_resources],
        package_dir = "/etc/localstack/init/ready.d/",
    )

    oci_image(
        name = name + "-image",
        testonly = True,
        base = "@localstack//:localstack",
        env = {
            "LOCALSTACK_LAMBDA_IGNORE_ARCHITECTURE": "1",
            "LOCALSTACK_LAMBDA_KEEPALIVE_MS": "0",
        },
        tars = [
            ":" + name + "-init-resources-tar",
            ":" + name + "-handlers-tar",
        ],
    )

    oci_load(
        name = name + "-load",
        testonly = True,
        image = ":" + name + "-image",
        repo_tags = [repo_tag],
    )

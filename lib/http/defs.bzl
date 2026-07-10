"""Macros for building stub server test container images."""

load("@rules_oci//oci:defs.bzl", "oci_image", "oci_load")
load("@rules_pkg//:pkg.bzl", "pkg_tar")

def stub_server_image(name, deploy_jar, repo_tag = None, visibility = None):
    """Creates a JRE-based OCI image for a stub HTTP server.

    Generates the following targets:
      - {name}-tar: pkg_tar containing the deploy JAR
      - {name}-image: oci_image layered on top of the Temurin 21 JRE base
      - {name}-load: oci_load to make the image available to Testcontainers

    Args:
        name: Base name for the generated targets and the container directory.
        deploy_jar: Label for the deploy JAR to include.
        repo_tag: Docker repo tag for the loaded image. Defaults to "bazel/{name}:latest".
        visibility: Visibility for the {name}-load target.
    """
    if not repo_tag:
        repo_tag = "bazel/" + name + ":latest"

    pkg_tar(
        name = name + "-tar",
        testonly = True,
        srcs = [deploy_jar],
        package_dir = "/opt/code/" + name + "/",
    )

    oci_image(
        name = name + "-image",
        testonly = True,
        base = "@temurin21jre//:temurin21jre",
        tars = [
            ":" + name + "-tar",
        ],
    )

    oci_load(
        name = name + "-load",
        testonly = True,
        image = ":" + name + "-image",
        repo_tags = [repo_tag],
        visibility = visibility,
    )

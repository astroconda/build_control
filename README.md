This project contains several files to partially define and facilitate an
automated conda package building system for the astroconda project.

- `/jenkins` : Jenkins CI pipeline build script(s) ([Groovy](http://www.groovy-lang.org/))
- `/manifests` : Provided as a git submodule pointing to https://github.com/astroconda/build_manifests. Lists of conda package & metapackage recipes, and associated metadata for various astroconda release types ([YAML](http://yaml.org/))
- `/patches` : Patches to apply to conda machinery immediately after installation of conda to fix bugs that have not yet been corrected in available/applicable releases.

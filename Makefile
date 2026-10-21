.PHONY: device-test

# Builds, installs and runs tests/device on the TV; ARGS passes options
# through, e.g. make device-test ARGS="--themes TERMINAL --no-build".
device-test:
	tests/device/run.sh $(ARGS)

OUTDIR := out

JAVAC_OPTS := -Xlint:all $(JAVAC_OPTS)
CLASSPATH := $(lastword $(sort $(wildcard ../bcprov-jdk15on-*.jar)))


.PHONY : default all tests clean

default : all

all : $(OUTDIR)
	find . -name '*.java' -print0 | xargs -0 -r javac $(JAVAC_OPTS) -d '$(OUTDIR)' -cp '$(CLASSPATH)'

clean :
	rm -rf '$(OUTDIR)'

$(OUTDIR) :
	mkdir -p '$(OUTDIR)'

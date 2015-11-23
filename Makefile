LIBDIR := ..
OUTDIR := out

JAVAC := javac
JAVAC_OPTS := -source 1.6 -target 1.6 -Xlint:all $(JAVAC_OPTS)
JAR := jar

NAME := coinfloor-library
PACKAGES := uk
MAINCLASS :=
LIBRARIES := $(strip \
	$(wildcard $(LIBDIR)/bcprov-jdk15on-*.jar) $(wildcard $(LIBDIR)/bcprov.jar) \
	)

EMPTY :=
SPACE := $(EMPTY) $(EMPTY)
CLASSPATH := $(subst $(SPACE),:,$(LIBRARIES))

COMMIT := $(shell git describe --always --dirty)
ifeq ($(COMMIT),)
JARFILE := $(OUTDIR)/$(NAME).jar
else
JARFILE := $(OUTDIR)/$(NAME)-g$(COMMIT).jar
endif

.PHONY : default all tests clean

default : all

all : $(JARFILE)

clean :
	rm -rf '$(OUTDIR)'

$(OUTDIR) :
	mkdir -p '$(OUTDIR)'

$(JARFILE) : $(OUTDIR) $(shell find $(PACKAGES))
	rm -rf $(addprefix '$(OUTDIR)'/,$(PACKAGES))
	find $(PACKAGES) -name '*.java' -print0 | xargs -0 -r $(JAVAC) $(JAVAC_OPTS) -d '$(OUTDIR)' -cp '$(CLASSPATH)'
	echo 'Class-Path: $(subst $(LIBDIR)/,,$(LIBRARIES))' > '$(OUTDIR)/Manifest'
	$(JAR) -cfme '$(JARFILE)' '$(OUTDIR)/Manifest' '$(MAINCLASS)' -C '$(OUTDIR)' $(PACKAGES)

SRCDIR := src/main/java
LIBDIR := ..
OUTDIR := target

JAVAC := javac
JAVAC_OPTS := -source 1.6 -target 1.6 $(JAVAC_OPTS)
JAR := jar

NAME := coinfloor-library
MAINCLASS :=
LIBRARIES := $(wildcard $(addprefix $(LIBDIR)/, \
	))

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

$(JARFILE) : $(shell find '$(SRCDIR)' -type d -o -name '*.java')
	rm -rf '$(OUTDIR)'
	mkdir -p '$(OUTDIR)/classes'
	find '$(SRCDIR)' -name '*.java' -print0 | xargs -0 -r $(JAVAC) $(JAVAC_OPTS) -sourcepath '$(SRCDIR)' -d '$(OUTDIR)/classes' -cp '$(CLASSPATH)'
	echo 'Class-Path: $(subst $(LIBDIR)/,,$(LIBRARIES))' > '$(OUTDIR)/Manifest'
	$(JAR) -cfme '$(JARFILE)' '$(OUTDIR)/Manifest' '$(MAINCLASS)' -C '$(OUTDIR)/classes' .

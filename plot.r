library('ggplot2')
library('outliers')
library('dplyr')
library('tikzDevice')

tokenizer <- read.csv("javaTokenizer.csv")
tokenizer$service <- "javaTokenizer"

parser <- read.csv("javaParser.csv")
parser$service <- "javaParser"

outline <- read.csv("javaOutliner.csv")
outline$service <- "javaOutliner"

data <- rbind(tokenizer,parser,outline)

# data <- data %>%
#   group_by(file, service) %>%
#   mutate(isoutlier=outlier(roundtrip,logical=TRUE)) %>%
#   filter(isoutlier == FALSE) %>%
#   filter(roundtrip/1e6 <= 500)

p <- ggplot(data, aes(y=roundtrip/1e6, x=bytes, linetype=service)) +
  geom_smooth(color="black") +
  ylab("roundtrip (ms)") +
  xlab("file size (bytes)") +
  theme(text = element_text(size=8),
        legend.position=c(.2, .7))

ggsave(p,filename="roundtrip.png")

tikz(file="/Users/svenkeidel/Documents/monto/paper/roundtrip.tex", width=3.2, height=2.5)
print(p)
dev.off()

# plotRoundtrip <- function(data) function(classfile) {

#   dat <- data[data$file==classfile,]

#   lines <- dat$lines[1]

#   # remove outliers
#   dat <- ddply(dat, "service", function(d) data.frame(roundtrip=rm.outlier(d$roundtrip)))

#   p <- ggplot(dat, aes(roundtrip/1e6, colour = factor(service))) +
# #    ggtitle(paste(classfile,", ", lines, " lines", sep="")) +
#     geom_density() +
#     scale_colour_discrete(name = "services") +
#     xlab("roundtrip (ms)") +
#     xlim(0,100) +
#     theme(text = element_text(size=7),
#           legend.position=c(.7, .7))
#   dir.create("plots", showWarnings = FALSE)

#   ggsave(p,filename=paste("plots",sub("java", "png", classfile), sep="/"))

#   options(tikzDocumentDeclaration = "\\documentclass[8pt]{article}")

#   tikz(file=paste("plots",sub("java", "tex", classfile), sep="/"),
#        width=3.2,
#        height=2.5)
#   print(p)
#   dev.off()

# }

# lapply(unique(data$file),plotRoundtrip(data))

library('ggplot2')
library('outliers')
library('dplyr')
library('tikzDevice')

tokenizer <- read.csv("javaTokenizer.csv")
tokenizer$Service <- "Java Highlighting"

parser <- read.csv("javaJavaCCParser.csv")
parser$Service <- "Java Parser"

outline <- read.csv("javaOutliner.csv")
outline$Service <- "Java Outliner"

data <- rbind(tokenizer,parser,outline)

filelengths <- read.csv("filelengths.csv")
filelengths$file <- filelengths$filename
# filelengths.csv was generated with cloc --csv --by-file <code-dir> --report-file=filelengths.csv
data <- full_join(data, filelengths)

# data$Service <- data[data$Service == "Java Outliner",]

data <- data %>%
  group_by(file,Service) %>%
  mutate(isoutlier=outlier(overall,logical=TRUE)) %>%
  filter(isoutlier == FALSE)

data <- data %>%
  group_by(file,Service) %>%
  summarise(overall=mean(overall),code=mean(code),blank=mean(blank),comment=mean(comment),productive=mean(productive))

data$lines <- data$code + data$blank + data$comment

# p <- ggplot(data, aes(y=overall/1e6, x=bytes, linetype=Service)) +
#   geom_point(color="black") +
#   ylab("response time (ms)") +
#   xlab("file size (bytes)") +
#   theme(text = element_text(size=8),
#         legend.position=c(.2, .7))
# ggsave(p,filename="roundtrip.png")
# tikz(file="/Users/svenkeidel/Documents/monto/paper/roundtrip.tex", width=3.2, height=2.5)
# print(p)
# dev.off()

p <- ggplot(data, aes(y=overall/1e6, x=lines, linetype=Service)) +
  geom_smooth(color="black") +
  ylab("response time (ms)") +
  xlab("lines of code") +
  theme(text = element_text(size=8),
        legend.position=c(.2, .7))
ggsave(p,filename="roundtriploc.png")
tikz(file="/Users/svenkeidel/Documents/monto/paper/roundtriploc.tex", width=3.2, height=2.5)
print(p)
dev.off()

# p <- ggplot(data, aes(y=(overall-productive)/1e6, x=bytes, linetype=Service)) +
#   geom_point(color="black") +
#   ylab("overhead (ms)") +
#   xlab("file size (bytes)") +
#   theme(text = element_text(size=8),
#         legend.position=c(.2, .7))
# ggsave(p,filename="overhead.png")
# tikz(file="/Users/svenkeidel/Documents/monto/paper/overhead.tex", width=3.2, height=2.5)
# print(p)
# dev.off()

# p <- ggplot(data, aes(y=(overall-productive)/1e6, x=lines, linetype=Service)) +
#   geom_smooth(color="black") +
#   ylab("overhead (ms)") +
#   xlab("lines of code") +
#   theme(text = element_text(size=8),
#         legend.position=c(.2, .7))
# ggsave(p,filename="overheadloc.png")
# tikz(file="/Users/svenkeidel/Documents/monto/paper/overheadloc.tex", width=3.2, height=2.5)
# print(p)
# dev.off()
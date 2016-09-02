library('ggplot2')
library('outliers')
library('dplyr')

# filelengths.csv was generated with cloc --csv --by-file <code-dir> --report-file=filelengths.csv
filelengths <- read.csv("filelengths.csv")[ ,1:5]
filelengths$file <- filelengths$filename
filelengths$lines <- filelengths$code + filelengths$blank + filelengths$comment
print(nrow(filelengths))
print(sum(filelengths$lines))
print(min(filelengths$lines))
print(max(filelengths$lines))
print(median(filelengths$lines))
print(mean(filelengths$lines))

p <- ggplot(filelengths, aes(x=factor(0), y=lines)) +
  geom_boxplot() +
  xlab("") +
  scale_x_discrete(breaks = NULL) +
  coord_flip() +
  theme_bw() +
  theme(text = element_text(size=20),
        panel.grid.minor = element_line(colour = "#808080",size=0.2), 
        panel.grid.major = element_line(colour = "#808080",size=0.4))

ggsave(p,filename="filelengths.png", height=2.5,dpi=120)

tokenizer <- read.csv("javaHighlighter.csv")
tokenizer$Service <- "Java Highlighting"

parser <- read.csv("javaJavaCCParser.csv")
parser$Service <- "Java Parser"

outline <- read.csv("javaOutliner.csv")
outline$Service <- "Java Outliner"

data <- rbind(tokenizer,parser,outline)

data <- full_join(data, filelengths)

data <- data %>%
  group_by(file,Service) %>%
  mutate(isoutlier=outlier(overall,logical=TRUE)) %>%
  filter(isoutlier == FALSE)

data <- data %>%
  group_by(file,Service) %>%
  summarise(overall=mean(overall),code=mean(code),blank=mean(blank),comment=mean(comment),productive=mean(productive))

p <- ggplot(data, aes(y=overall/1e6, x=code+blank+comment, linetype=Service)) +
  geom_smooth(color="black") +
  ylab("response time (ms)") +
  xlab("lines of code") +
  theme_bw() +
  theme(text = element_text(size=14),
        legend.position=c(.25, .7),
        panel.grid.minor = element_line(colour = "#808080",size=0.2), 
        panel.grid.major = element_line(colour = "#808080",size=0.4),
        legend.key.width= unit(3,"line"))
ggsave(p,filename="roundtriploc.png")

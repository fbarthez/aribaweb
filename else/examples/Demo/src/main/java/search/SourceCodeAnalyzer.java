package search;

import java.util.HashMap;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.util.CharTokenizer;
import org.apache.lucene.util.AttributeFactory;

/** An Analyzer that filters tokenizes sequences matching Character.isJavaIdentifierPart() and then toLowers them. */
public final class SourceCodeAnalyzer extends Analyzer
{

    private static final String[] STOP_WORDS = {
      // Java
        "public","private","protected","interface",
        "abstract","implements","extends","null", "new",
        "switch","case", "default" ,"synchronized" ,
        "do", "if", "else", "break","continue","this",
        "assert" ,"for","instanceof", "transient",
        "final", "static" ,"void","catch","try",
        "throws","throw","class", "finally","return",
        "const" , "native", "super","while", "import",
        "package" ,"true", "false",
      // English
        "a", "an", "and", "are","as","at","be", "but",
        "by", "for", "if", "in", "into", "is", "it",
        "no", "not", "of", "on", "or", "s", "such",
        "that", "the", "their", "then", "there","these",
        "they", "this", "to", "was", "will", "with"
    };

    // TODO FP: I've modified this method to make it compile with lucene 5.2, pretty much w/o knowing what it does
    static PerFieldAnalyzerWrapper analyzerForField (String fieldName, Analyzer defaultAnalyzer)
    {
        if (defaultAnalyzer == null) defaultAnalyzer = new StandardAnalyzer();
        Map<String,Analyzer> analyzerPerField = new HashMap<String, Analyzer>();
        analyzerPerField.put(fieldName, new SourceCodeAnalyzer());
        PerFieldAnalyzerWrapper analyzer = new PerFieldAnalyzerWrapper(new SourceCodeAnalyzer(), analyzerPerField);
        
        return analyzer;
    }


    public TokenStream tokenStream (String fieldName, AttributeFactory reader)
    {
        // return new StopFilter(new CodeTokenizer(reader), STOP_WORDS);
        return new CodeTokenizer(reader);
    }

    static class CodeTokenizer extends CharTokenizer
    {
        public CodeTokenizer ()
        {
            super();
        }

        // TODO FP: I've modified this constructor to make it compile with lucene 5.2, pretty much w/o knowing what it does
        public CodeTokenizer (AttributeFactory in)
        {
            super(in);
        }

        protected boolean isTokenChar (char c)
        {
            return Character.isJavaIdentifierPart(c);
        }

        protected char normalize (char c)
        {
            return Character.toLowerCase(c);
        }

        @Override
        protected boolean isTokenChar(int arg0) {
            // TODO Auto-generated method stub
            return false;
        }
    }

    @Override
    protected TokenStreamComponents createComponents(String arg0) {
        // TODO Auto-generated method stub
        return null;
    }
}

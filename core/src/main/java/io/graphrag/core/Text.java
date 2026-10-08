package io.graphrag.core;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
public final class Text {
    private Text() {}
    public static String normalize(String s) {
        return Normalizer.normalize(Objects.requireNonNull(s),Normalizer.Form.NFKC).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").replaceAll("^[\\p{P}\\p{S}\\s]+|[\\p{P}\\p{S}\\s]+$","").trim();
    }
    public static String id(String... s) { return UUID.nameUUIDFromBytes(String.join("\u0000",s).getBytes(StandardCharsets.UTF_8)).toString(); }
    private static final Set<String> STOP=Set.of("the","and","with","from","that","this","what","which","where","when","does","were","have","about","their","into","how","why","who","are","was","did","for","can","you","tell","all","across");
    public static Set<String> tokens(String s) { Set<String> t=new TreeSet<>(); Matcher m=Pattern.compile("[\\p{L}\\p{N}]{3,}").matcher(normalize(s)); while(m.find()) if(!STOP.contains(m.group()))t.add(m.group()); return t; }
    public static double keyword(String query,String text) { var q=tokens(query); if(q.isEmpty())return 0; var t=tokens(text); return q.stream().filter(t::contains).count()/(double)q.size(); }
    public static List<String> split(String text,int size,int overlap) {
        if(size<1 || overlap<0 || overlap>=size)throw new IllegalArgumentException("Invalid split settings");
        List<String> out=new ArrayList<>(); for(int start=0;start<text.length();) { int end=Math.min(text.length(),start+size); out.add(text.substring(start,end)); if(end==text.length())break; start=end-overlap; } return out;
    }
    public static double cosine(List<Double> a,List<Double>b) { if(a.size()!=b.size() || a.isEmpty())return 0; double dot=0,aa=0,bb=0; for(int i=0;i<a.size();i++){dot+=a.get(i)*b.get(i);aa+=a.get(i)*a.get(i);bb+=b.get(i)*b.get(i);}return aa==0||bb==0?0:dot/Math.sqrt(aa*bb); }
}

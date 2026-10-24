package com.ticknova.ai;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Checks objectively comparable details, not semantic entailment or overall groundedness. */
final class AiEvidenceGuard {
    private static final String ADDRESS_CHARS="[^\\s<>\"'`*,;，。；！？、（）【】()\\]}]+";
    private static final Pattern ADDRESS=Pattern.compile("https?://"+ADDRESS_CHARS+"|www\\."+ADDRESS_CHARS
        +"|(?:[a-z][a-z0-9-]*\\.)+[a-z]{2,63}(?::\\d{1,5})?(?:/"+ADDRESS_CHARS+")?"
        +"|(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?",Pattern.CASE_INSENSITIVE);
    private static final Pattern QUANTITY=Pattern.compile("(\\d+(?:\\.\\d+)?|[零〇一二两三四五六七八九十百千]+(?:点[零〇一二两三四五六七八九]+)?)\\s*"
        +"(毫秒|秒钟?|分钟|小时|工作日|天|周|个月|月|年|次|MiB|KiB|GiB|KB|MB|GB|%|°C|ms|minutes?|seconds?|hours?)",Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE=Pattern.compile("```(?:[a-zA-Z0-9_-]*\\n)?(.*?)```|`([^`\\r\\n]+)`",Pattern.DOTALL);
    private AiEvidenceGuard() {}

    static boolean supported(String answer,List<String> evidence){
        Set<String> addresses=new HashSet<>(),quantities=new HashSet<>();List<String> normalized=new ArrayList<>();
        for(String item:evidence){String text=normalize(item);normalized.add(text);addresses.addAll(addresses(text));quantities.addAll(quantities(text));}
        String text=normalize(answer);
        if(!addresses.containsAll(addresses(text))||!quantities.containsAll(quantities(text)))return false;
        var code=CODE.matcher(text);
        while(code.find()){
            String snippet=compact(code.group(1)!=null?code.group(1):code.group(2));
            if(!snippet.isEmpty()&&normalized.stream().map(AiEvidenceGuard::compact).noneMatch(source->source.contains(snippet)))return false;
        }
        return true;
    }
    private static String normalize(String text){return Normalizer.normalize(text,Normalizer.Form.NFKC);}
    private static String compact(String text){return text.replaceAll("\\s+"," ").strip();}
    private static Set<String> addresses(String text){
        Set<String> result=new HashSet<>();var matcher=ADDRESS.matcher(text);
        while(matcher.find())result.add(matcher.group().replaceAll("[.,;:!?]+$",""));
        return result;
    }
    private static Set<String> quantities(String text){
        Set<String> result=new HashSet<>();var matcher=QUANTITY.matcher(text);
        while(matcher.find()){
            BigDecimal value=number(matcher.group(1));String unit=matcher.group(2).toLowerCase(Locale.ROOT);
            int multiplier=switch(unit){case "分钟","minute","minutes"->60;case "小时","hour","hours"->3600;case "天"->86400;case "周"->604800;default->1;};
            if(Set.of("秒","秒钟","second","seconds","分钟","minute","minutes","小时","hour","hours","天","周").contains(unit))unit="seconds";
            if(unit.equals("毫秒"))unit="ms";
            result.add(value.multiply(BigDecimal.valueOf(multiplier)).stripTrailingZeros().toPlainString()+":"+unit);
        }
        return result;
    }
    private static BigDecimal number(String text){
        if(Character.isDigit(text.charAt(0)))return new BigDecimal(text);
        String[] decimal=text.split("点",-1);StringBuilder value=new StringBuilder();
        if(decimal[0].chars().noneMatch(ch->"十百千".indexOf(ch)>=0)){
            for(char ch:decimal[0].toCharArray())value.append(digit(ch));
        }else{
            int total=0,current=0;
            for(char ch:decimal[0].toCharArray()){
                int unit=switch(ch){case '十'->10;case '百'->100;case '千'->1000;default->0;};
                if(unit>0){total+=(current==0?1:current)*unit;current=0;}else current=digit(ch);
            }
            value.append(total+current);
        }
        if(decimal.length==2){value.append('.');for(char ch:decimal[1].toCharArray())value.append(digit(ch));}
        return new BigDecimal(value.toString());
    }
    private static int digit(char ch){return switch(ch){case '零','〇'->0;case '一'->1;case '二','两'->2;case '三'->3;case '四'->4;case '五'->5;case '六'->6;case '七'->7;case '八'->8;case '九'->9;default->throw new IllegalArgumentException("Unsupported numeral");};}
}

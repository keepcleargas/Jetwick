/**
 * Copyright (C) 2010 Peter Karich <jetwick_@_pannous_._info>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.jetwick.tw.cmd;

import de.jetwick.data.UrlEntry;
import de.jetwick.solr.SolrTweet;
import de.jetwick.util.AnyExecutor;
import de.jetwick.tw.TweetDetector;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

/**
 * @author Peter Karich, peat_hal 'at' users 'dot' sourceforge 'dot' net
 */
public class TermCreateCommand implements AnyExecutor<SolrTweet> {

    private boolean termRemoving = true;

    public TermCreateCommand() {
        //http://en.wikipedia.org/wiki/Phonetic_algorithm
        //http://en.wikipedia.org/wiki/Approximate_string_matching
        //http://en.wikipedia.org/wiki/Kullback%E2%80%93Leibler_divergence
        //BUT we are using a technic from TextProfileSignature:
        // create a list of tokens and their frequency, separated by spaces, in the order of decreasing frequency.
        // This list is then submitted to an MD5 hash calculation.
        // Only suited for short strings: JaroWinklerDistance, LevensteinDistance,new NGramDistance.
        // Use relative termMinFrequency!!
    }

    public TermCreateCommand(boolean termRemoving) {
        this.termRemoving = termRemoving;
    }

    @Override
    public SolrTweet execute(SolrTweet tw) {
        // HINT: do only modify the current tweets' quality!
        double qual = tw.getQuality();
        calcTermsWithoutNoise(tw);

        int maxTerms = 0;
        for (Entry<String, Integer> entry : tw.getTextTerms().getSortedFreqLimit(0.05f)) {
            if (entry.getValue() > maxTerms)
                maxTerms = entry.getValue();

            if (entry.getKey().equals("beknowledge"))
                qual = qual * 0.9;
        }

        // term occurs more than one time on the current tweet?
        if (maxTerms > 4) {
            qual = Math.max(0, 100 - maxTerms * 8);
            tw.addQualAction("MT,");
        }

        // now calculate quality via comparing to existing tweets
        StringFreqMap otherTerms = new StringFreqMap();
        StringFreqMap otherLangs = new StringFreqMap();
        tw.setQuality((int) qual);
        qual = checkSpamInExistingTweets(tw, otherTerms, otherLangs);
        tw.setQuality((int) qual);

        // prepare indexing and remove terms which do NOT occur in other tweets (i.e. are 'unimportant')
        if (termRemoving) {
            Iterator<String> iter = tw.getTextTerms().keySet().iterator();
            while (iter.hasNext()) {
                String term = iter.next();
                Integer integ = otherTerms.get(term);
                if (integ == null || integ < 1)
                    iter.remove();
            }
        }

        // language detection
        tw.setLanguage(detectLanguage(tw, otherLangs));
        return tw;
    }

    public String detectLanguage(SolrTweet tweet, StringFreqMap languages) {
        if (tweet.getLanguages().size() > 0) {
            List<Entry<String, Integer>> list = tweet.getLanguages().getSorted();
            int index = 0;

            Entry<String, Integer> lEntry = list.get(index);
            if (tweet.getLanguages().size() > index + 1 && TweetDetector.UNKNOWN_LANG.equals(lEntry.getKey())) {
                index++;
                lEntry = list.get(index);
            }

            if (tweet.getLanguages().size() > index + 1 && !TweetDetector.UNKNOWN_LANG.equals(lEntry.getKey())) {
                if (lEntry.getValue() - 1 < list.get(index + 1).getValue())
                    // the second language seems also important
                    return TweetDetector.UNKNOWN_LANG;
            }

            if (languages.containsKey(lEntry.getKey()) || lEntry.getValue() > 2)
                return lEntry.getKey();
        }
        return TweetDetector.UNKNOWN_LANG;
    }

    public double checkSpamInExistingTweets(SolrTweet currentTweet,
            StringFreqMap mergedTerms, StringFreqMap mergedLangs) {
        double qual = currentTweet.getQuality();

        // TODO use jaccard index for url titles too!?
        StringFreqMap urlTitleMap = new StringFreqMap();
        StringFreqMap urlMap = new StringFreqMap();
        for (SolrTweet older : currentTweet.getFromUser().getOwnTweets()) {
            for (UrlEntry entry : older.getUrlEntries()) {
                urlTitleMap.inc(entry.getResolvedTitle(), 1);
                urlMap.inc(entry.getResolvedUrl(), 1);
            }
        }

        boolean sameUrl = false;
        for (SolrTweet older : currentTweet.getFromUser().getOwnTweets()) {
            if (older == currentTweet)
                continue;
            
            // create tags to decide if tags of currentTweet are important
            calcTermsWithoutNoise(older);
            // count only one term per tweet
            mergedTerms.addOne2All(older.getTextTerms());
            // count languages as they appear
            mergedLangs.addValue2All(older.getLanguages());

            // compare only to older tweets            
            if (currentTweet.getCreatedAt().getTime() <= older.getCreatedAt().getTime())
                continue;

            // we don't need the signature because we have the jaccard index
            // performance improvement of comparison: use int[] instead of byte[]
//            if (Arrays.equals(tw.getTextSignature(), older.getTextSignature()))
//                qual = qual * 0.7;

            // use Jaccard index
            int a = currentTweet.getTextTerms().andSize(older.getTextTerms());
            double b = currentTweet.getTextTerms().orSize(older.getTextTerms());
            double ji = a / b;

            if (ji >= 0.8) {
                // nearly equal terms
                qual *= SolrTweet.QUAL_BAD / 100.0;
                currentTweet.addQualAction("JB," + older.getTwitterId() + ",");
            } else if (ji >= 0.6 && currentTweet.getQualReductions() < 3) {
                // e.g. if 3 of 4 terms occur in both tweets
                qual *= SolrTweet.QUAL_LOW / 100.0;
                currentTweet.addQualAction("JL," + older.getTwitterId() + ",");
            }

            if (!sameUrl) {
                for (UrlEntry entry : older.getUrlEntries()) {
                    Integer titleCounts = urlTitleMap.get(entry.getResolvedTitle());
                    if (titleCounts != null) {
                        if ((titleCounts == 2 || titleCounts == 3) && currentTweet.getQualReductions() < 3) {
                            sameUrl = true;
                            qual *= SolrTweet.QUAL_LOW / 100.0;
                            currentTweet.addQualAction("TL," + older.getTwitterId() + ",");
                        } else if (titleCounts > 3) {
                            sameUrl = true;
                            // tweeted about the identical url title!
                            qual *= SolrTweet.QUAL_BAD / 100.0;
                            currentTweet.addQualAction("TB," + older.getTwitterId() + ",");
                        }
                    } else
                        titleCounts = 0;

                    // if title is identical then url isn't necessary to compare
                    if (titleCounts < 2) {
                        Integer urlCounts = urlMap.get(entry.getResolvedUrl());
                        if (urlCounts != null) {
                            if ((urlCounts == 2 || urlCounts == 3) && currentTweet.getQualReductions() < 3) {
                                sameUrl = true;
                                qual *= SolrTweet.QUAL_LOW / 100.0;
                                currentTweet.addQualAction("UL," + older.getTwitterId() + ",");
                            } else if (urlCounts > 3) {
                                sameUrl = true;
                                // tweeted about the identical url title!
                                qual *= SolrTweet.QUAL_BAD / 100.0;
                                currentTweet.addQualAction("UB," + older.getTwitterId() + ",");
                            }
                        }
                    }
                }
            }
        }
        return qual;
    }

    public void calcTermsWithoutNoise(SolrTweet tw) {
        if (tw.getTextTerms().size() > 0)
            return;

        TweetDetector extractor = new TweetDetector().runOne(tw.getText());
        tw.setTextTerms(extractor.getTerms());
        tw.setLanguages(extractor.getLanguages());


        // we don't need the signature because we have the jaccard index
//        String strForSig = "";
//        for (Entry<String, Integer> entry : tw.getTextTerms().getSortedFreqLimit(0.05f)) {
//            strForSig += entry.getKey() + " " + entry.getValue() + ";";
//        }
//
//        // use Lookup3Signature which is faster but only 64 bit?
//        MD5Signature sig = new MD5Signature();
//        sig.add(strForSig);
//
//        // Helper.sigToString(sig.getSignature)
//        tw.setTextSignature(sig.getSignature());
    }
}

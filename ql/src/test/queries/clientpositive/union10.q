set hive.map.aggr = true;

-- SORT_QUERY_RESULTS

-- union case: all subqueries are a map-reduce jobs, 3 way union, same input for all sub-queries, followed by filesink

create table tmptable(key string, value int);

explain 
insert overwrite table tmptable
  select unionsrc.key, unionsrc.value FROM (select 'tst1' as key, count(1) as value from src s1
                                        UNION  ALL  
                                            select 'tst2' as key, count(1) as value from src s2
                                        UNION ALL
                                            select 'tst3' as key, count(1) as value from src s3) unionsrc;


insert overwrite table tmptable
  select unionsrc.key, unionsrc.value FROM (select 'tst1' as key, count(1) as value from src s1
                                        UNION  ALL  
                                            select 'tst2' as key, count(1) as value from src s2
                                        UNION ALL
                                            select 'tst3' as key, count(1) as value from src s3) unionsrc;


select * from tmptable x sort by x.key;


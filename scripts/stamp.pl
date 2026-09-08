use Time::HiRes qw(time);
$| = 1;
while (my $l = <STDIN>) { printf("%.6f\t%s", time, $l); }
